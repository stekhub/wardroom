# 0012. Data model

- Status: Accepted; per-user data superseded by [0018](0018-no-user-reference-in-mvp.md)
- Date: 2026-11-18

## Context

The data model is the foundation for synchronization, threading, read state and posting. NNTP has two article identities: the `Message-ID` is global, while article numbers are local to one group on one server. A crossposted article has one `Message-ID` and a different number in each group.

The first version targets a single NNTP server and a single Wardroom user, without making multiple servers or users hard to add later (brief §13).

The full table and column reference is in [data-model.md](../data-model.md).

## Decision

**Users and server**

- One Wardroom user, used only for authentication to Wardroom.
- The MVP supports exactly one NNTP server. The server is an instance-level record, not owned by the user. The single-server limit is enforced in the application, not in the schema.
- All content tables (newsgroups, article locations) reference `server_id`, so a second server needs no schema change.
- Per-user data (subscriptions, read state) references `user_id`.

**Server configuration**

- Host, port and TLS mode rather than a URL.
- TLS modes: `IMPLICIT` (default, port 563) and `NONE` (port 119). `STARTTLS` can be added later as a further value of the same column.
- Authentication: `NONE` or `USER_PASS` (`AUTHINFO USER/PASS`, RFC 4643). SASL is added only on demand.
- `USER_PASS` requires `IMPLICIT` TLS, so credentials are never sent in plaintext (brief §5). `NONE` TLS is only for servers without authentication.
- The password is stored encrypted with AES-256-GCM.
- `max_connections` feeds the per-server connection limit ([ADR 0006](0006-resilience.md)). `credentials_invalid` is set on response 481 and cleared when credentials are edited.

**Newsgroups**

- `newsgroup` is the server-specific group: one row per group per server. The group name is the logical identity across servers; there is no separate logical newsgroup table.
- Groups that disappear from the server's list are flagged as removed, never deleted (brief §9).

**Articles**

- `article` holds one row per `Message-ID`, with overview metadata and, once fetched, the article content.
- `article_location` holds `(newsgroup_id, article_number)` for each place the article appears. A crosspost is one article with several locations.
- When the server expires an article, its location is marked expired rather than deleted, so the article remains part of the group.

**Threads**

- No persisted thread entity. Threads are built from `References`, and a derived, recomputable `thread_root_id` on `article` supports sorting and counting.
- A referenced article that is not stored locally becomes a **stub**: an `article` row with only its `Message-ID`. Threads stay connected, the UI shows the stub as not available, and its content can be fetched by `Message-ID` on demand.

**Headers**

- `Subject` and `From` are decoded (RFC 2047) when stored.
- `Date` is parsed and stored as UTC epoch milliseconds.
- `Message-ID` is stored verbatim, including angle brackets, and compared exactly.

**Read state**

- A `read_state` row per user and article means "read"; no row means unread.
- Articles from the first synchronization are therefore unread. A "mark all as read" action per group is part of the MVP.
- Because read state references the article, reading a crosspost marks it read in every group.

### Changes to the brief

- Brief §3.2 and §4 list one or more servers for the MVP. The MVP supports one server; the model stays multi-server ready.
- Brief §7 lists separate `Newsgroup` and `ServerNewsgroup` entities and places the server under the user. The server is instance-level, `newsgroup` is server-specific, and the logical group is identified by name.
- Brief §7 `SyncState` is represented by synchronization columns on `newsgroup` and `nntp_server` ([ADR 0013](0013-synchronization-and-caching.md)).

## Consequences

- Crossposts are stored once and read state is consistent across groups.
- Synchronization works on article numbers through `article_location`; the UI works on articles.
- With several servers later, the same `Message-ID` on two servers remains one `article` with locations on each server's groups.
- If multiple Wardroom users are introduced, NNTP credentials move from `nntp_server` into a per-user account table, because provider accounts are personal (connection limits, posting accountability, group access). Content tables keyed by `server_id` stay unchanged.
- With a shared cache, users of one instance could infer which groups others read. This is an accepted property for small self-hosted instances.
- Thread recomputation must be cheap and idempotent, because it runs after synchronization and cleanup.
