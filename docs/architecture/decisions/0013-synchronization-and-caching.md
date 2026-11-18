# 0013. Synchronization and caching

- Status: Accepted; per-subscription initial depth superseded by [0019](0019-initial-sync-depth-instance-only.md), group list trigger by [0020](0020-scheduled-group-list-refresh.md), single synchronized range by [0021](0021-jump-to-date.md), retention by [0023](0023-cache-size-limit.md)
- Date: 2026-11-18

## Context

Wardroom keeps a local copy of server data so the UI is fast and works from the database (brief §3.6). The data has four layers with different sizes and change rates: the group list, overviews of subscribed groups, article content, and stubs for referenced articles that are not stored locally. Each needs its own trigger and retention.

## Decision

| Data | When fetched |
|---|---|
| Group list (`LIST ACTIVE`, `LIST NEWSGROUPS`) | When the server is set up, then only on manual refresh |
| Overviews of subscribed groups (`GROUP`, `OVER`) | Background synchronization at a fixed interval, plus manual refresh |
| Article content (`ARTICLE`) | Only when an article is opened |
| Stub content (`ARTICLE <message-id>`) | Only when a stub is opened |

**Overview synchronization**

- Incremental, continuing from the group's synchronized range. Older ranges are fetched on request ("load older discussions").
- The interval is an instance setting, default 15 minutes, editable on the settings page.
- The first synchronization of a subscription fetches the latest N overviews. N defaults to 500 as an instance setting on the settings page, and can be overridden per subscription.
- The synchronization state of a group is updated only after the batch completes (brief §9).

**Article content**

- Fetched lazily, only on demand. There is no background prefetch, including for thread roots.
- Fetched content is cached and not fetched again.

**Retention**

- The MVP keeps cached data for as long as the group is subscribed.
- Unsubscribing deletes the group's locations and every article no longer located in any subscribed group, including its read state. The exact steps are in [data-model.md](../data-model.md#lifecycle-rules).
- When the server expires articles (the low watermark rises), their locations are marked expired and cached content stays readable.
- A general cleanup rule (for example by age) is deferred and will be decided separately.

**Frontend**

TanStack Query caches API responses for the browser session only. It is not a data store and does not affect the rules above.

## Consequences

- Opening an article that is not yet cached requires the server to be reachable.
- Thread cards can show an excerpt only when the thread root's content has been fetched. Brief §3.4 lists the excerpt as a card element; without prefetching it is shown only once available.
- Rough sizing: about 300 bytes of metadata and 2 to 4 KB of content per article. Ten groups with 100 articles a day produce roughly 110 MB of metadata per year; content grows only with articles actually opened.
- Resubscribing to a group starts a fresh first synchronization; earlier read state for its articles is lost.
