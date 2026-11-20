# 006. Threading

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.4, §3.5, [ADR 0012](../../architecture/decisions/0012-data-model.md), [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0021](../../architecture/decisions/0021-jump-to-date.md), [spec 005](../005-article-synchronization/spec.md)

## Scope

Building threads from locally stored article metadata: parent and root of each article, stubs for missing articles, fallbacks for articles without `References`, recomputation, and the per-group thread figures used by thread cards. Showing threads is covered by the discussion view specification.

## User stories

- As the owner, I see a group as discussions, with replies under the article they answer.
- As the owner, a discussion stays connected even when some of its articles are not stored locally.
- As the owner, old discussions from clients that did not set `References` are still grouped sensibly.

## Functional requirements

**Parent and root**

- An article's parent is the last Message-ID in its `References`. Its thread root is its topmost ancestor.
- Every Message-ID in `References` that is not stored locally becomes a stub. A stub's parent is the Message-ID listed before it in that `References`; the first one has none. The chain stays connected even when articles in the middle are missing, including when posting clients shortened `References` (RFC 5536 allows dropping middle entries).
- An article's own `References` decides its parent. A stub's parent is inferred from the first article that created it; when the stub is filled in, its own `References` takes over.
- A new article never changes the parent of an existing article.
- A link that would create a cycle is ignored; the article becomes a thread root.

**Parsing `References`**

- Only tokens of the form `<…>` are read; anything else in the header is ignored.
- Duplicates and the article's own Message-ID are dropped.
- Message-IDs that contain whitespace or control characters are dropped, because Message-IDs are later sent to the server in commands.
- Message-IDs are compared exactly ([data model](../../architecture/data-model.md)).

**`In-Reply-To`**

- `In-Reply-To` is not part of the overview, so it is known only after the article's content has been fetched.
- It is used only when the article has no `References`: its first Message-ID is taken as the parent, and the article is moved into that thread.

**Subject fallback**

- Applies only to articles that have neither `References` nor a known `In-Reply-To`, and whose subject starts with a reply prefix.
- The article attaches to the newest earlier thread root in the same group with the same normalized subject, posted at most 30 days before it. Stubs are not matched. Without a match, the article is its own thread root.
- Subject normalization: leading reply prefixes are removed repeatedly (`Re:`, `Aw:`, `Sv:`, `Antw:`, also with a counter such as `Re[2]:` or `Re^2:`), case-insensitively; whitespace is collapsed and trimmed; the comparison ignores case.
- The fallback is evaluated again on every recomputation of the group, so a matching thread root fetched later by load older or jump to date is found.

**Recomputation**

- After each stored chunk of a synchronization, load or jump, the threads that received articles are recomputed, together with the subject fallback of the group.
- After the unsubscribe cleanup, the threads that had an article in the group are recomputed ([data model](../../architecture/data-model.md#lifecycle-rules)).
- After an article's content is fetched, its thread is recomputed if it has no `References` and its `In-Reply-To` is now known.
- Recomputation is idempotent: running it twice gives the same result.

**A thread in a group**

- A thread appears in a group if at least one non-stub article of it has a location in that group, expired locations included.
- Message count: non-stub articles of the thread located in the group.
- Latest activity: the newest date among those articles; articles without a date are left out.
- Card article: the thread root if it is a non-stub article located in the group, otherwise the oldest such article of the thread. It provides the subject, author and original date shown on the card; without a date the card shows "date unknown".
- A reply that changes the subject stays in its thread.
- Crossposted threads: an article of the thread that is stored locally but located only in other groups is still part of the thread structure. The thread page shows it in its place, marked with the group it was posted to; it does not count towards the group's message count or unread count.

**Order within a thread**

- Replies to the same parent are ordered by date, oldest first. Articles without a date are placed by article number in the group.

**Thread identity**

- The root of a thread can change, for example when load older fetches the original post. Links to a thread therefore accept the ID of any article in it and resolve to its current root.

## API contracts

None of its own. The discussion view specification exposes thread lists and thread pages built on this structure.

## Technical design

- Threading runs in the backend, in Java, over the articles of the affected threads; the result is written to `parent_id` and `thread_root_id`.
- A full recomputation of a group processes its articles by article number, oldest first, so the result does not depend on fetch order.
- Thread figures (count, latest activity, card article) are computed by query, grouped by `thread_root_id`. If this becomes slow, stored figures can be added later.
- Indexes on `article.parent_id` and `article.thread_root_id`.

## Data model changes

- New column `article.parent_id` (nullable, FK `article`): the direct parent, a real article or a stub.
- `article.thread_root_id` is now defined as the topmost ancestor, which can be a stub.

## Error handling

| Situation | Behavior |
|---|---|
| Malformed `References` | Unreadable parts ignored; the article is threaded from the valid Message-IDs |
| Cycle | The link closing the cycle is ignored; logged |
| Recomputation fails | Logged; the previous `parent_id` and `thread_root_id` stay; the next synchronization retries |

## Security considerations

- Header values are untrusted. Parsing must not fail or loop on very long or crafted `References`; at most the last 100 Message-IDs are used.

## Testing requirements

Unit tests of the threading function:

- Parent and root from `References`; stubs for missing middle articles; shortened `References`.
- A filled-in stub takes its parent from its own `References`.
- Cycles, duplicates, the article's own Message-ID, malformed tokens and Message-IDs with whitespace or control characters.
- `In-Reply-To` used only without `References`.
- Subject fallback: prefix variants, the 30-day limit, no match against stubs, no match without a reply prefix.
- Idempotence, and the same result regardless of fetch order.

Integration tests against a real SQLite database:

- Threads are recomputed after synchronization and load; a later-loaded original post becomes the root and links to the old root resolve to it.
- Message count and latest activity count only articles located in the group; undated articles are left out of latest activity.
- After unsubscribe, threads of remaining groups and still-referenced stubs are intact.

## Implementation tasks

1. Flyway migration for `article.parent_id` and the indexes.
2. `References` parsing and subject normalization.
3. Threading function: parents, stubs, cycles, `In-Reply-To` and subject fallback.
4. Recomputation hooks after synchronization chunks, load, jump, content fetch and unsubscribe.
5. Queries for thread figures and the card article per group, and resolution of any article ID to its thread root.
