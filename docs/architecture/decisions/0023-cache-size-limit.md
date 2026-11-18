# 0023. Cache size limit

- Status: Accepted
- Date: 2026-11-18
- Supersedes: the deferred "general cleanup rule" in [ADR 0013](0013-synchronization-and-caching.md)

## Context

ADR 0013 keeps cached data for as long as a group is subscribed and defers a general cleanup rule. Without one, the database grows without bound. Article content is fetched only when an article is opened (2 to 4 KB each), so content grows slowly; overviews of very busy groups grow with every synchronization, whether or not anything is opened.

A size limit is one setting the owner understands and bounds disk use directly. A rule by age would need a different value per group to have the same effect.

## Decision

**Setting**

- `cache.max_size_gb`, default 5, range 1–1000, in the synchronization section of the settings page. The section shows the current use next to it, for example "1.2 GB of 5 GB".

**Measurement**

- The size is the space the database actually uses: `(page_count − freelist_count) × page_size`. It is read with SQLite pragmas and costs no scan.
- The database file does not shrink when data is deleted; SQLite reuses the freed pages. No `VACUUM` is run.

**Cleanup job**

- Runs after each synchronization run and at startup.
- If the size is above the limit, it deletes article content until the size is below 90 % of the limit, so it does not run again after every synchronization.
- Deleting content sets `raw_headers`, `body_text`, `omitted_parts` and `content_fetched_at` to null. Headers from the overview, threads, locations and read state stay, so every thread stays complete.
- Order: first articles that still have a non-expired location (the server still has them, so they can be fetched again), then the rest; within each, oldest `date` first, undated articles first.
- Never deleted: the owner's own articles while they wait for their location ([spec 009](../../specs/009-article-posting/spec.md)).
- Content is deleted in batches of 1,000 articles, one transaction each, so synchronization and reading continue between batches (WAL mode, [ADR 0003](0003-persistence.md)).
- A cleaned article behaves like one never opened: its content is fetched when it is opened. If the server no longer has it, it shows "no longer available on the server" ([spec 007](../../specs/007-article-reading/spec.md)).

**Limit not reachable**

- If the size is still above the limit when no content is left to delete, the settings section shows a warning: the stored headers alone exceed the limit; unsubscribing from large groups frees space. Removing whole old threads is not part of the MVP.

## Consequences

- Disk use stays near the limit in normal use; the limit is a target, not a guarantee, when header data alone is too large.
- Expired articles were readable for as long as the group was subscribed (ADR 0013); with this rule, their content can be deleted once everything still on the server has been cleaned. This happens only on very large caches.
- Excerpts of thread cards disappear with the content of their card article until it is opened again.
- A partial index on `article(date)` for articles with content keeps selecting the next batch fast.
- Freeing 10 % of a 5 GB cache deletes roughly 150,000 articles' content: about 150 short transactions in the background.
