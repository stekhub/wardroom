# 004. Subscriptions

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.3, [UI concept](../../product/ui-concept.md), [ADR 0012](../../architecture/decisions/0012-data-model.md), [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0018](../../architecture/decisions/0018-no-user-reference-in-mvp.md), [ADR 0019](../../architecture/decisions/0019-initial-sync-depth-instance-only.md), [spec 003](../003-newsgroup-discovery/spec.md)

## Scope

Subscribing and unsubscribing, the subscription list in the sidebar, and marking a group as read. How overviews are synchronized, including the first synchronization and the synchronization settings, is covered by the article synchronization specification.

## User stories

- As the owner, I subscribe to a group with one click and see immediately that I am subscribed.
- As the owner, I see my subscribed groups in the sidebar with their unread counts.
- As the owner, I unsubscribe from a group I no longer read, and its cached data is removed.
- As the owner, I mark all articles in a group as read.
- As the owner, I still see and read a subscribed group after the server has removed it.

## Functional requirements

**Subscribing**

- With the subscribe toggle in a discover row or the group header ([UI concept](../../product/ui-concept.md#interaction-rules)). One click, no dialog.
- Subscribing starts the group's first synchronization immediately, in the background, with the instance's initial depth (ADR 0019).
- Subscribing to an already subscribed group has no effect, also when the server has removed the group since.
- Removed groups that are not subscribed cannot be subscribed to.

**Subscription list (sidebar)**

- All subscribed groups, alphabetically by name.
- Each entry shows the group icon ([Group Icons](../../product/group-icons.md)), the name, the unread count, and status icons: synchronization running, last synchronization failed (with the error on hover), removed from the server.
- Clicking an entry opens the group view.
- The list refreshes every 30 seconds, and every 2 seconds while a synchronization is running.

**Unread count**

- The number of articles with a location in the group that are not stubs and have no read state. Articles whose location has expired on the server still count, because their cached content stays readable. Ignored articles and the articles of hidden threads do not count ([spec 010](../010-ignoring/spec.md)).

**Badges**

- Moderated groups and groups that do not allow posting carry a badge in the group header and in discover rows.

**Unsubscribing**

- With the same toggle. A confirmation dialog states that the group's cached articles and read state will be deleted.
- Unsubscribing runs the unsubscribe cleanup ([data model](../../architecture/data-model.md#lifecycle-rules)). Crossposted articles that are still located in another subscribed group are kept.
- Unsubscribing from the group view opens the discover view.

**Mark all as read**

- In the group header. Marks every non-stub article located in the group as read, without confirmation.
- Because read state belongs to the article, crossposts are marked read in all groups (ADR 0012).

**Removed groups**

- A subscribed group that the server no longer lists stays subscribed and in the sidebar, marked as removed. Its cached articles stay readable; synchronization of it fails and is shown as such.

## API contracts

All endpoints require a session and the CSRF token for state-changing requests ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457).

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/subscriptions` | | `200` list of subscriptions |
| `PUT /api/groups/{id}/subscription` | | `204` subscribed (also if already subscribed, even when the group is removed); `404` unknown group; `409` group removed and not subscribed |
| `DELETE /api/groups/{id}/subscription` | | `204` unsubscribed (also if not subscribed); `404` unknown group |
| `POST /api/groups/{id}/mark-all-read` | | `204`; `404` unknown group; `409` not subscribed |

**Subscription**

```json
{
  "groupId": 42,
  "serverId": 1,
  "name": "comp.lang.java.programmer",
  "description": "Programming in the Java language.",
  "postingStatus": "ALLOWED",
  "removed": false,
  "unreadCount": 12,
  "sync": { "running": false, "lastSyncedAt": 1795000000000, "error": null }
}
```

`description` is `null` when the server gave none. The frontend needs it for the group icon, which is derived from name and description ([Group Icons](../../product/group-icons.md)); so a group has the same icon in the sidebar and in the discover view.

The `subscribed` flag in the group list of [spec 003](../003-newsgroup-discovery/spec.md) uses the same data.

## Technical design

- Subscribing stores the subscription and then hands the group to the synchronization job; the request does not wait for the synchronization.
- The unsubscribe cleanup runs in one transaction.
- Synchronization and unsubscribe of the same group never run at the same time. Unsubscribing cancels a running synchronization of the group and runs after the current batch.
- Mark all as read inserts read state for all unread articles of the group in one statement.
- Unread counts are computed by query. If this becomes slow, a stored counter can be added later.

## Data model changes

- `subscription.initial_sync_depth` is removed (ADR 0019). `subscription` holds only `newsgroup_id`, unique.

## Error handling

| Situation | Behavior |
|---|---|
| Unknown group ID | `404` |
| Subscribing to a removed group that is not subscribed | `409` |
| Mark all as read on a group that is not subscribed | `409` |
| First synchronization fails | Subscription stays; the sidebar shows the failure icon with the error |
| Unsubscribe while the group is synchronizing | The synchronization is cancelled; the unsubscribe completes |

## Security considerations

- No sensitive data is involved. Group names from the server are displayed as text only.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- Subscribing stores the subscription and starts the first synchronization; repeating it changes nothing.
- Subscribing to a removed group returns `409`; for a removed group that is subscribed already it returns `204`.
- Unsubscribing removes the group's locations, articles without remaining locations and their read state, and keeps crossposts located in another subscribed group.
- Unsubscribing during a running synchronization leaves no locations of the group behind.
- Unread count excludes stubs and read articles and includes expired locations.
- Mark all as read marks every non-stub article of the group, including crossposts.
- A subscribed group that becomes removed stays in the subscription list with `removed: true`.

Frontend component tests:

- The toggle changes state immediately on subscribe and asks for confirmation on unsubscribe.

## Implementation tasks

1. Flyway migration for `subscription` and `read_state`.
2. Subscribe and unsubscribe endpoints, including the cleanup and coordination with synchronization.
3. Subscription list endpoint with unread counts and synchronization status.
4. Mark all as read endpoint.
5. Frontend: sidebar subscription list with group icons, large group icon in the group header, subscribe toggle, unsubscribe confirmation, badges, mark all as read.
