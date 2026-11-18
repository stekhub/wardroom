# 005. Article synchronization

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.3, §3.6, §9, [ADR 0005](../../architecture/decisions/0005-nntp-client-library.md), [ADR 0006](../../architecture/decisions/0006-resilience.md), [ADR 0012](../../architecture/decisions/0012-data-model.md), [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0019](../../architecture/decisions/0019-initial-sync-depth-instance-only.md), [ADR 0020](../../architecture/decisions/0020-scheduled-group-list-refresh.md), [ADR 0021](../../architecture/decisions/0021-jump-to-date.md), [ADR 0023](../../architecture/decisions/0023-cache-size-limit.md), [spec 004](../004-subscriptions/spec.md)

## Scope

Fetching article overviews of subscribed groups: the background schedule, the first synchronization, manual refresh, loading older and newer discussions, jumping to a date, server expiry and renumbering, the cache size limit, and the synchronization settings. Fetching article content is covered by the article reading specification; building threads by the threading specification; showing threads, including the date view, by the discussion view specification.

## User stories

- As the owner, new articles in my groups appear without my doing anything.
- As the owner, I refresh a group immediately when I expect new posts.
- As the owner, I load older discussions of a group step by step.
- As the owner, I pick a date and read the group as it was from then on, even years ago.
- As the owner, I set how often Wardroom synchronizes and how much a first synchronization fetches.
- As the owner, I set how much disk space the cache may use.

## Functional requirements

**Schedule**

- All subscribed groups are synchronized every `sync.interval_minutes` (default 15, range 5–1440).
- A run that is still going when the next is due causes that next run to be skipped.
- Per server, up to `max_connections − 1` groups (at least 1) are synchronized in parallel. The remaining connection is kept for interactive actions: opening articles, manual refresh, loading, jumping and the connection test.

**Synchronizing one group**

1. `GROUP` reads the article count, low and high watermark, which are stored on the group.
2. Without any synchronized range (first synchronization): fetch the overviews from `max(low, high − depth + 1)` to `high`, where depth is `sync.initial_depth` (default 500, range 50–10,000).
3. Otherwise: fetch the overviews from the end of the newest range + 1 to `high`, in chunks of 1,000, oldest first.
4. After each chunk, its articles and the extended range are stored in one transaction, so an interrupted synchronization continues where it stopped.
5. On success `last_synced_at` is set and `last_sync_error` cleared, and the group's threads are recomputed (threading specification).

**Manual refresh**

- The refresh button in the group header synchronizes that group immediately, outside the schedule.

**Load older and load more**

- "Load older discussions" extends the range being viewed downwards by up to `sync.initial_depth` overviews; in the normal group view that is the newest range.
- In a date view, "load more" extends the range upwards by the same amount.
- A step stops at the server's low or high watermark or at the neighboring range, and merges with it ([ADR 0021](../../architecture/decisions/0021-jump-to-date.md)).
- Articles newly stored by load older, load more and jump to date are marked read ([ADR 0022](../../architecture/decisions/0022-older-articles-arrive-read.md)).

**Jump to date**

- The owner picks a date in the group header. The frontend sends the start of that day in the browser's time zone.
- Wardroom searches for the first article on or after that time, then fetches up to `sync.initial_depth` overviews from there forward, and the group view shows the group from that date, oldest first.
- If the start falls inside an existing range, nothing is fetched.
- If the date is older than the oldest article on the server, the view starts at the oldest available article and states its date.
- If the date is in the future or after the newest article, the view shows the newest articles.

**Expiry and renumbering**

- When the low watermark rises, locations below it are marked expired; their cached content stays readable.
- When the high watermark is lower than the end of the newest range, the server has renumbered the group. The group's locations and ranges are deleted with the unsubscribe cleanup steps 2–4, keeping the subscription ([data model](../../architecture/data-model.md#lifecycle-rules)), and a first synchronization runs. Read state of articles not fetched again is lost.

**Cache size limit**

- After each synchronization run and at startup, Wardroom checks the database size against `cache.max_size_gb`. Above the limit, it deletes cached article content until the size is below 90 % of the limit ([ADR 0023](../../architecture/decisions/0023-cache-size-limit.md)).
- Content of articles the server still has goes first, then expired ones; oldest first within each. Headers, threads and read state are kept; the owner's articles waiting for their location are never cleaned.
- A cleaned article is fetched again when opened.
- If the limit cannot be reached by deleting content, the synchronization section shows a warning suggesting to unsubscribe from large groups.

**Settings**

On the settings page, synchronization section:

| Setting | Default | Range |
|---|---|---|
| Synchronization interval (`sync.interval_minutes`) | 15 minutes | 5–1440 |
| First synchronization depth (`sync.initial_depth`) | 500 articles | 50–10,000 |
| Group list refresh interval (`sync.group_list_interval_hours`) | 24 hours | 1–168 |
| Maximum cache size (`cache.max_size_gb`) | 5 GB | 1–1000 |

Changing the depth affects only later first synchronizations and load steps. The section also shows the current cache use, for example "1.2 GB of 5 GB". Lowering the limit runs the cleanup job at once.

## API contracts

All endpoints require a session and the CSRF token for state-changing requests ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457). Synchronization status per group is part of the subscription list ([spec 004](../004-subscriptions/spec.md)).

| Method and path | Request | Responses |
|---|---|---|
| `POST /api/groups/{id}/sync` | | `202` started or already running; `404`; `409` not subscribed or credentials invalid |
| `POST /api/groups/{id}/jump` | `{ "from": <epoch ms> }` | `200` jump result; `404`; `409`; `502` server error |
| `POST /api/groups/{id}/ranges/{rangeId}/extend` | `{ "direction": "OLDER" \| "NEWER" }` | `200` range; `404`; `409`; `502` |
| `GET /api/settings` | | `200` settings |
| `PUT /api/settings` | Settings | `200`; `400` out of range |

Jump and extend run during the request and return when the overviews are stored.

**Range**

```json
{ "rangeId": 7, "firstNumber": 120400, "lastNumber": 120899, "atOldest": false, "atNewest": false }
```

`atOldest` and `atNewest` tell the frontend whether further loading in that direction is possible.

**Jump result**

```json
{ "range": { "…": "range as above" }, "startNumber": 120400, "startDate": 1420070400000, "clampedToOldest": false }
```

**Settings**

`GET` additionally returns the read-only `cacheUsedBytes` and `cacheLimitUnreachable`.

```json
{ "syncIntervalMinutes": 15, "initialDepth": 500, "groupListIntervalHours": 24, "maxCacheSizeGb": 5 }
```

## Technical design

**Overviews**

- Fetched with `OVER`, or `XOVER` if `CAPABILITIES` does not list `OVER` ([open question 1](../../open-questions.md)).
- The field order is read once per server with `LIST OVERVIEW.FMT` and kept in memory. If the command is not supported, the order of RFC 3977 is used.
- For each overview: `Subject` and `From` are decoded (RFC 2047); `Date` is parsed to UTC epoch milliseconds, or stored as null if it cannot be parsed; `Message-ID`, `References`, bytes and lines are stored as received.
- A `Message-ID` that already exists gets a new location: the article is crossposted, or was a stub and is filled in.
- If the article already has a location in the same group under another number, that location gets the new number.
- Overviews without `Message-ID` are skipped and logged.

**Date search**

- Lower-bound binary search over article numbers between the low and high watermark.
- Each probe fetches the overviews of a window of 20 numbers at the midpoint and uses the first one with a parseable date. Empty windows (cancelled or missing articles) move the window up; after five empty windows the search fails.
- The search stops when the remaining interval is shorter than the depth; the start is its lower end.
- The probed overviews are not stored.

**Cache cleanup**

- The size is `(page_count − freelist_count) × page_size`, read with SQLite pragmas. No `VACUUM` is run; freed pages are reused.
- Content is deleted in batches of 1,000 articles, one transaction each, selected through a partial index on `article(date)` for articles with content. Synchronization and reading continue between batches.
- The job never runs twice at the same time.

**Concurrency**

- Synchronization, load, jump and unsubscribe of the same group never run at the same time ([spec 004](../004-subscriptions/spec.md)).
- Background synchronization uses at most `max_connections − 1` connections per server; interactive actions use the remaining one and wait for a free connection otherwise, for at most 30 seconds; then they fail with `502`.
- Timeouts, retries and circuit breaker values are in [ADR 0026](../../architecture/decisions/0026-nntp-timeouts-and-retry-values.md).

## Data model changes

- New table `synced_range` (`newsgroup_id`, `first_number`, `last_number`); `newsgroup.oldest_synced_number` and `newest_synced_number` are removed ([ADR 0021](../../architecture/decisions/0021-jump-to-date.md)).
- New instance setting `sync.group_list_interval_hours` ([ADR 0020](../../architecture/decisions/0020-scheduled-group-list-refresh.md)).
- New instance setting `cache.max_size_gb`; partial index on `article(date)` for articles with cached content ([ADR 0023](../../architecture/decisions/0023-cache-size-limit.md)).

## Error handling

| Situation | Behavior |
|---|---|
| `411` no such group | The group's `last_sync_error` says the group is not available on the server; other groups continue |
| `481` authentication failed | `credentials_invalid` set; the run stops for this server |
| Unreachable, timeout, `400`, `502` | Retried with backoff as in ADR 0006; then `last_sync_error` set; the range keeps the chunks already stored |
| Circuit breaker open | The run skips the server; groups keep their previous status |
| Jump or extend fails on the server | `502` with the cause; nothing is stored |
| Date search finds no usable dates | `502` saying the group's dates could not be read |
| Malformed overview line | Skipped and logged; the chunk continues |

## Security considerations

- Overview data is untrusted and stored as text; it is never interpreted as HTML.
- Header decoding must not fail on invalid encodings; invalid sequences are replaced.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- First synchronization fetches the newest `depth` overviews; a group with fewer articles fetches all.
- Incremental synchronization fetches only new numbers, in chunks; an interruption after a chunk resumes from there.
- A crosspost gets a second location; a stub is filled in; a known article arriving under another number in the same group keeps one location with the new number.
- Field order from `LIST OVERVIEW.FMT`, and the fallback when it is not supported; `XOVER` fallback.
- Load older and load more stop at the watermarks and merge with neighboring ranges.
- Articles newly stored by load older, load more and jump to date are read; articles already stored keep their read state.
- Jump to date: finds the right start in a group with ascending dates; tolerates wrong dates and empty windows; clamps to the oldest article; a start inside an existing range fetches nothing; probes are not stored.
- Expiry marks locations below the new low watermark; renumbering resets the group and runs a first synchronization.
- `411`, `481`, timeouts and an open circuit breaker produce the documented status.
- At most `max_connections − 1` background connections per server; an interactive action gets a connection while all groups are synchronizing.
- Overlapping runs are skipped; settings are validated against their ranges.
- Cache cleanup: above the limit, content is deleted down to 90 %, non-expired before expired, oldest first; headers and read state stay; pending own posts are kept; a cleaned article is fetched again on open; the warning appears when only headers remain.

## Implementation tasks

1. Flyway migration for `article`, `article_location`, `synced_range` and the settings keys.
2. NNTP adapter: `GROUP`, `OVER`/`XOVER` with `LIST OVERVIEW.FMT`, overview parsing and header decoding.
3. Group synchronization: first and incremental synchronization in chunks, ranges, expiry, renumbering.
4. Scheduler with per-server parallelism and the reserved interactive connection.
5. Load older, load more and jump to date with the date search.
6. Cache cleanup job and size measurement.
7. Endpoints for manual refresh, jump, extend and settings.
8. Frontend: refresh button, load older and load more, date picker in the group header, synchronization section on the settings page with cache use and warning.
