# Data Model

Reference for Wardroom's persistent data. Decisions and their rationale are in [ADR 0012](decisions/0012-data-model.md), [ADR 0013](decisions/0013-synchronization-and-caching.md) and [ADR 0014](decisions/0014-plain-text-article-display.md). This document is updated as the model evolves; column types and indexes are finalized in the migrations.

## Overview

```text
subscription >── newsgroup >── nntp_server
                     │
                     ├─< synced_range
                     └─< article_location >── article ──< read_state
```

`ignore_rule` stands alone; it matches articles by value, not by reference ([spec 010](../specs/010-ignoring/spec.md)).

`wardroom_user` stands alone: it is used only for authentication and referenced by no other table ([ADR 0018](decisions/0018-no-user-reference-in-mvp.md)).

All tables use integer surrogate primary keys (`id`). Timestamps are UTC epoch milliseconds.

## Tables

### `wardroom_user`

The local Wardroom account (authentication only). The MVP has exactly one row.

| Column | Notes |
|---|---|
| `username` | Unique |
| `password_hash` | Argon2id |
| `failed_logins` | Consecutive failed logins, for the login throttling ([spec 011](../specs/011-login-throttling-and-session-timeout/spec.md)) |
| `login_blocked_until` | End of the waiting time of the throttling; nullable |
| `totp_secret` | Secret of two-factor authentication, encrypted with the key file ([spec 012](../specs/012-two-factor-authentication/spec.md), [ADR 0035](decisions/0035-two-factor-authentication.md)); nullable |
| `totp_enabled` | Whether a login needs a code |
| `totp_last_used_period` | The 30-second period of the last accepted code, so a code works once; nullable |

Sessions are stored in the Spring Session JDBC tables ([spec 001](../specs/001-user-authentication/spec.md)).

### `recovery_code`

The one-time recovery codes of the account ([spec 012](../specs/012-two-factor-authentication/spec.md)). The table is emptied when two-factor authentication is turned off and when an account is created.

| Column | Notes |
|---|---|
| `code_hash` | SHA-256 of the code; unique |

### `nntp_server`

Instance-level server configuration. The MVP allows exactly one row (enforced in the application).

| Column | Notes |
|---|---|
| `name` | Display label |
| `host` | |
| `port` | Default from TLS mode: 563 for `IMPLICIT`, 119 for `NONE` |
| `tls_mode` | `IMPLICIT` (default), `NONE`; `STARTTLS` later |
| `auth_method` | `NONE`, `USER_PASS`; `USER_PASS` requires `IMPLICIT` |
| `username` | Nullable |
| `password_encrypted` | AES-256-GCM, versioned format; nullable |
| `max_connections` | Per-server connection limit |
| `credentials_invalid` | Set on response 481, cleared when credentials are edited |
| `groups_refreshed_at` | Last successful group list refresh |
| `groups_refresh_error` | Last refresh failure, nullable |
| `posting_name`, `posting_email`, `posting_signature` | Posting identity, nullable until first set ([spec 009](../specs/009-article-posting/spec.md)) |
| `posting_host`, `posting_port`, `posting_tls_mode` | The separate posting server ([spec 002](../specs/002-nntp-server-configuration/spec.md)); all empty when posts go to the server itself |
| `posting_auth_method` | Empty when the posting server uses the login of the server; otherwise `NONE` or `USER_PASS` |
| `posting_username`, `posting_password_encrypted` | The posting server's own login, nullable; the password in the format of `password_encrypted` |

### `newsgroup`

A group as it exists on one server. Unique on `(server_id, name)`.

| Column | Notes |
|---|---|
| `server_id` | FK `nntp_server` |
| `name` | Logical identity across servers |
| `description` | From `LIST NEWSGROUPS`, nullable |
| `posting_status` | Posting allowed, not allowed, or moderated |
| `low_watermark`, `high_watermark` | Article number range reported by the server |
| `last_synced_at` | Last successful synchronization |
| `last_sync_error` | Last failure, nullable |
| `removed_at` | Set when the group disappears from the server's list; never deleted for that reason |
| `activity_older_at`, `activity_older_high_watermark` | The older baseline of the activity: a time and the high watermark the group list refresh read then ([spec 003](../specs/003-newsgroup-discovery/spec.md)). Nullable until the first refresh |
| `activity_younger_at`, `activity_younger_high_watermark` | The younger baseline; it becomes the older one when it is 7 days old |
| `articles_per_day` | The activity, computed by the group list refresh from the older baseline. Nullable: not known while the older baseline is less than a day old |

`high_watermark` is also written by the synchronization of the group; the baselines are written by the group list refresh only.

### `synced_range`

A range of article numbers whose overviews have been fetched ([ADR 0021](decisions/0021-jump-to-date.md)). A group has none before its first synchronization and any number afterwards. Ranges of a group never overlap; touching or overlapping ranges are merged.

| Column | Notes |
|---|---|
| `newsgroup_id` | FK `newsgroup` |
| `first_number`, `last_number` | Inclusive |

### `subscription`

Unique on `newsgroup_id`.

| Column | Notes |
|---|---|
| `newsgroup_id` | FK `newsgroup` |

### `article`

One row per `Message-ID`. Unique on `message_id`.

| Column | Notes |
|---|---|
| `message_id` | Verbatim, including angle brackets; compared exactly |
| `is_stub` | True for referenced articles not stored locally; all other metadata is null |
| `subject` | RFC 2047 decoded |
| `from` | RFC 2047 decoded |
| `author_key` | Email address from `from`, lowercased and trimmed, else the display name; null for stubs; indexed ([spec 010](../specs/010-ignoring/spec.md)) |
| `date` | UTC epoch milliseconds; partial index for articles with cached content ([ADR 0023](decisions/0023-cache-size-limit.md)) |
| `references` | Raw `References` value |
| `bytes`, `lines` | From the overview |
| `parent_id` | FK `article`; direct parent, a real article or a stub; derived, recomputable ([spec 006](../specs/006-threading/spec.md)) |
| `thread_root_id` | Topmost ancestor, can be a stub; derived, recomputable; indexed ([spec 008](../specs/008-discussion-view/spec.md)) |
| `raw_headers` | Full header block as received; null until fetched |
| `body_text` | Decoded plain text, UTF-8; null until fetched |
| `body_flowed` | True when the text is `format=flowed` (RFC 3676); it is stored as received and unwrapped at display time; null until fetched ([spec 007](../specs/007-article-reading/spec.md)) |
| `body_delsp` | True when the flowed text was sent with `DelSp=yes`: the space before a soft line break is not part of the text; null until fetched |
| `body_truncated` | True when a limit stopped the processing, so the stored text is not all of the article; null until fetched ([spec 007](../specs/007-article-reading/spec.md)) |
| `omitted_parts` | Parts not displayed (name, media type, size); null until fetched |
| `content_fetched_at` | Null until fetched |
| `content_unavailable_at` | Set when the server answered `430`; cleared by a successful retry ([spec 007](../specs/007-article-reading/spec.md)) |
| `posted_from_newsgroup_id` | FK `newsgroup`; set for articles posted with Wardroom, shown as "Sent" until they have a location ([spec 009](../specs/009-article-posting/spec.md)) |

### `article_location`

Where an article appears. Unique on `(newsgroup_id, article_number)` and on `(newsgroup_id, article_id)`.

| Column | Notes |
|---|---|
| `newsgroup_id` | FK `newsgroup` |
| `article_number` | Server- and group-specific |
| `article_id` | FK `article` |
| `expired_at` | Set when the number falls below the server's low watermark |

### `read_state`

A row means the article has been read; no row means unread. Unique on `article_id`.

| Column | Notes |
|---|---|
| `article_id` | FK `article`; deleted with the article |
| `read_at` | |

### `ignore_rule`

An author or thread the owner ignores ([spec 010](../specs/010-ignoring/spec.md)). Unique on `(kind, value)`.

| Column | Notes |
|---|---|
| `kind` | `AUTHOR`, `THREAD` |
| `value` | Author key, or the `Message-ID` of the thread root when the rule was created |
| `label` | Display name or thread subject, for the settings page |
| `created_at` | |

### `app_setting`

Instance-wide settings as key-value pairs, editable on the settings page.

| Key | Default |
|---|---|
| `sync.interval_minutes` | 15 |
| `sync.group_list_interval_hours` | 24 ([ADR 0020](decisions/0020-scheduled-group-list-refresh.md)) |
| `sync.initial_depth` | 500 ([ADR 0019](decisions/0019-initial-sync-depth-instance-only.md)) |
| `cache.max_size_gb` | 5 ([ADR 0023](decisions/0023-cache-size-limit.md)) |
| `cache.limit_enabled` | `true` ([ADR 0034](decisions/0034-cache-size-limit-can-be-switched-off.md)) |

One key is not a setting and is not shown on the settings page: `app.version`, the version of Wardroom that used the database last. Wardroom writes it at every start and uses it to name the backup before an update ([ADR 0032](decisions/0032-application-version-in-backup-names.md)).

## Lifecycle rules

- **Unsubscribe** deletes, in one transaction:
  1. the subscription;
  2. the group's article locations;
  3. articles without any remaining location (their read state goes with them), and the stubs of every thread that had an article in the group, except articles posted from another group that are still waiting for their location ([spec 009](../specs/009-article-posting/spec.md));
  4. then deletes the group's synchronized ranges and recomputes the threads that had an article in the group, recreating stubs that are still referenced by remaining articles. Threads without an article in the group are not touched.

  Crossposted articles with a location in another subscribed group are kept. The `newsgroup` row stays, as part of the group list.
- **Server expiry** marks locations expired; cached content stays readable until the cache size limit removes it.
- **Cache size limit** deletes the cached content (`raw_headers`, `body_text`, `omitted_parts`, `content_fetched_at`) of the oldest articles when the database exceeds `cache.max_size_gb`; metadata, threads and read state stay ([ADR 0023](decisions/0023-cache-size-limit.md)). The flags of the content (`body_flowed`, `body_delsp`, `body_truncated`) are emptied with it. With `cache.limit_enabled` off, nothing is deleted ([ADR 0034](decisions/0034-cache-size-limit-can-be-switched-off.md)).
- **Group removed from the server** sets `removed_at`; subscriptions and cache are kept.
- **Account creation** deletes all sessions and the NNTP username and password, setting the server's authentication to `NONE` ([ADR 0017](decisions/0017-password-reset.md)).
- **Server removal** deletes the server and everything that belongs to it: groups, subscriptions, article locations, articles without remaining locations and their read state ([spec 002](../specs/002-nntp-server-configuration/spec.md)).
