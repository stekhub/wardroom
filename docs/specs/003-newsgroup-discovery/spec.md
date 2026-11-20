# 003. Newsgroup discovery

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.1, §3.3, §9, [ADR 0006](../../architecture/decisions/0006-resilience.md), [ADR 0012](../../architecture/decisions/0012-data-model.md), [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0020](../../architecture/decisions/0020-scheduled-group-list-refresh.md), [spec 002](../002-nntp-server-configuration/spec.md)

## Scope

Fetching the server's group list with descriptions, keeping it up to date, and browsing and searching it. Subscribing is covered by the subscriptions specification.

## User stories

- As the owner, after setting up the server I see its groups without further action.
- As the owner, I search groups by name and description, so that I find groups on a topic.
- As the owner, new groups appear and removed groups are marked without my doing anything.
- As the owner, I refresh the group list manually and see while it is running.
- As the owner, I see when the list was last refreshed and whether the last refresh failed.

## Functional requirements

**Fetching the group list**

- Triggered when a server is created ([spec 002](../002-nntp-server-configuration/spec.md)), by manual refresh, and on a schedule: by default every 24 hours after the last successful refresh, set on the settings page (`sync.group_list_interval_hours`, 1–168). A failed scheduled refresh is attempted again after one hour ([ADR 0020](../../architecture/decisions/0020-scheduled-group-list-refresh.md)).
- `LIST ACTIVE` is read as a stream and stored in batches. For each group: name, low and high watermark, posting status.
- Then `LIST NEWSGROUPS` fetches descriptions, unless `CAPABILITIES` shows the server does not support it. If the server rejects the command, descriptions stay as they are and the refresh still succeeds.
- Groups not listed by `LIST NEWSGROUPS` keep their previous description.
- Some servers send something else in place of a description. Such a group has no description, and a stored one is removed:
  - the name of the group itself, compared without regard to case;
  - the rest of a `LIST ACTIVE` line, that is two numbers and a status. This is logged as a protocol anomaly.
- At most one refresh per server runs at a time.

**Posting status**

| `LIST ACTIVE` status | Stored as |
|---|---|
| `y` | Posting allowed |
| `m` | Moderated |
| `n`, and any other value (`x`, `j`, `=…`) | Posting not allowed |

**Removed and returning groups**

- A group missing from a complete `LIST ACTIVE` result gets `removed_at` set. It is never deleted for that reason (brief §9).
- A removed group that appears again has `removed_at` cleared.
- Groups are marked removed only after the complete list was received. A refresh that fails partway marks nothing.

**Status and progress**

- While a refresh runs, the UI shows that it is running and the number of groups received so far. The total is unknown in advance.
- The UI shows the time of the last successful refresh and the error of the last failed one.

**Browsing and searching**

- The group list page shows the server's groups alphabetically by name, paged with 50 per page.
- Each entry shows the group icon ([Group Icons](../../product/group-icons.md)), name, description, posting status and whether it is subscribed.
- Search: the query is split into words at whitespace. A group matches if each word appears in its name or its description. Matching is case-insensitive and finds parts of words.
- Removed groups do not appear in the group list. Subscribed groups that were removed remain visible in the subscriptions (subscriptions specification).

## API contracts

All endpoints require a session ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457).

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/servers/{id}/groups?q=&page=&size=` | `q` optional; `page` from 0; `size` 1–100, default 50 | `200` page of groups; `404` unknown server |
| `POST /api/servers/{id}/groups/refresh` | | `202` refresh started or already running; `404`; `409` credentials invalid |
| `GET /api/servers/{id}/groups/refresh` | | `200` refresh status; `404` |

**Page of groups**

```json
{
  "items": [
    {
      "id": 42,
      "name": "comp.lang.java.programmer",
      "description": "Programming in the Java language.",
      "postingStatus": "ALLOWED",
      "subscribed": false
    }
  ],
  "page": 0,
  "size": 50,
  "total": 1
}
```

`postingStatus` is `ALLOWED`, `NOT_ALLOWED` or `MODERATED`.

**Refresh status**

```json
{ "running": true, "groupsReceived": 18250, "refreshedAt": 1795000000000, "error": null }
```

## Technical design

- The refresh runs as a background job and uses one connection from the server's connection pool (ADR 0006).
- `LIST ACTIVE` lines are upserted in batches of 1,000, each in its own transaction, so an interrupted refresh leaves the groups received so far.
- The names received are collected in memory. After the complete list, groups not in it get `removed_at` set and listed groups have it cleared, in one transaction. A list of 200,000 names needs a few megabytes.
- On success `groups_refreshed_at` is set and `groups_refresh_error` cleared. On failure `groups_refresh_error` is set and `groups_refreshed_at` is unchanged.
- The running state and the received count are held in memory; after a restart no refresh is running.
- The refresh is not retried automatically; the owner refreshes again. Failures count toward the circuit breaker (ADR 0006).
- Descriptions are decoded as UTF-8; lines that are not valid UTF-8 are decoded as ISO-8859-1.
- Search uses `LIKE` with `%` around each word, with `%`, `_` and the escape character in the query escaped. SQLite's `LIKE` is case-insensitive for ASCII letters only; other letters must match exactly. A full-text index can be added if search becomes slow.

## Data model changes

None. The columns used are in the [data model](../../architecture/data-model.md): `newsgroup` (`name`, `description`, `posting_status`, watermarks, `removed_at`) and `nntp_server` (`groups_refreshed_at`, `groups_refresh_error`).

## Error handling

| Situation | Behavior |
|---|---|
| Server unreachable, timeout, `400`, `502` | Refresh fails; `groups_refresh_error` set; groups received so far are kept; nothing marked removed |
| Authentication fails (481) | Refresh fails; `credentials_invalid` set (ADR 0006) |
| Refresh requested while credentials are invalid | `409`; the frontend links to the server settings |
| Circuit breaker open | Refresh fails immediately with an error saying the server is temporarily unavailable |
| `LIST NEWSGROUPS` rejected | Ignored; refresh succeeds |
| Malformed `LIST ACTIVE` line | Line skipped and logged; refresh continues |
| Refresh requested while one is running | `202`; no second refresh starts |

## Security considerations

- Group names and descriptions come from the server and are untrusted. They are displayed as text only, never as HTML.
- The search query is passed as a bound parameter; wildcard characters are escaped.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- A refresh stores groups with watermarks, posting status and descriptions.
- Status mapping for `y`, `m`, `n`, `x`, `j` and `=…`.
- A server without `LIST NEWSGROUPS` support: refresh succeeds without descriptions; existing descriptions are kept.
- A description that repeats the group name, or that is the rest of a `LIST ACTIVE` line, is not stored and removes a stored one; the second is logged.
- A group missing from the list is marked removed; a returning group is cleared; a refresh that fails partway marks nothing and keeps received groups.
- Only one refresh runs at a time.
- Search: several words, matches in name or description, case-insensitive for ASCII, `%` and `_` in the query matched literally, paging and total count, removed groups excluded.
- Creating a server starts a refresh ([spec 002](../002-nntp-server-configuration/spec.md)).
- The scheduled refresh runs once the interval since the last successful refresh has passed, and one hour after a failed one.
- A list of 100,000 groups completes with bounded memory use.

## Implementation tasks

1. Flyway migration for `newsgroup`.
2. `LIST ACTIVE` and `LIST NEWSGROUPS` in the NNTP adapter, streaming, including the Commons Net spike items ([open question 1](../../open-questions.md)).
3. Refresh job: batch upsert, removed marking, status, single run per server.
4. Group list and refresh endpoints, search.
5. Frontend: group icon function with the keyword table from [Group Icons](../../product/group-icons.md), with unit tests for the matching order, country prefixes, ignored segments and the color hash.
6. Frontend: group list page with search and paging, group icons, refresh button, progress, last refresh time and error.
