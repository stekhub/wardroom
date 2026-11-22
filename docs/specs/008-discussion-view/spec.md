# 008. Discussion view

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.4, [UI concept](../../product/ui-concept.md), [ADR 0021](../../architecture/decisions/0021-jump-to-date.md), [ADR 0022](../../architecture/decisions/0022-older-articles-arrive-read.md), [spec 004](../004-subscriptions/spec.md), [spec 005](../005-article-synchronization/spec.md), [spec 006](../006-threading/spec.md), [spec 007](../007-article-reading/spec.md), [spec 010](../010-ignoring/spec.md)

## Scope

The thread list of a group with its cards, sorting and paging; the date view; the thread page with nested replies. How threads are built is in spec 006; how article text is fetched and displayed is in spec 007. Replying is part of the posting specification.

## User stories

- As the owner, I see a group's discussions as cards and can tell at a glance which ones have new messages.
- As the owner, I sort discussions by newest, latest activity or most discussed.
- As the owner, I read a group as it was at a given date.
- As the owner, I open a thread and follow nested replies, collapsing branches I am not interested in.
- As the owner, I mark a thread as read without opening every article.

## Functional requirements

**Threads in a group**

- A group shows every thread that has at least one non-stub article located in the group. The card article, message count and latest activity follow spec 006.

**Thread card**

- Subject, author with author icon ([UI concept](../../product/ui-concept.md#author-icons)) and date of the card article.
- The excerpt (spec 007).
- Message count and latest activity.
- "● N new": the unread non-stub articles of the thread located in the group; not shown when N is 0. Threads with unread articles have a bold subject.
- Times are shown relative for 7 days ("2 h ago"), then as a date; the exact time in the browser's time zone is shown on hover.
- Clicking the card opens the thread page.
- Card actions, shown on hover and reachable by keyboard: "Mark thread as read" (Lucide `check-check`) and "Ignore thread" ([spec 010](../010-ignoring/spec.md)).

**Sorting**

- **Newest:** by the date of the card article, newest first.
- **Latest activity** (default): by latest activity, newest first.
- **Most discussed:** by message count, highest first; ties by latest activity.
- Articles without a date count as oldest. Dates in the future are used as they are.
- The selected tab is part of the URL (`?sort=newest|activity|discussed`).

**Paging**

- The list loads 50 cards at a time as the owner scrolls.
- At the end of the list, "Load older discussions" (spec 005) is shown unless the server's low watermark has been reached.

**Date view**

- `/groups/{id}?until=YYYY-MM-DD` shows the threads whose card article is dated before the end of that day in the browser's time zone, newest first ([ADR 0033](../../architecture/decisions/0033-date-view-up-to-a-day.md)). The chosen day is included. Sort tabs are hidden.
- Only threads whose card article lies in the synchronized range found by the jump are shown. A thread without a date on its card article is not shown.
- A thread that began before the day shows all its loaded replies on the thread page, also those written after the day.
- The frontend calls jump (spec 005) when a day is chosen, to get the range; nothing is fetched when the date is already inside a range. The result is kept as long as the list of the view, so going back to the view from a thread does not call jump again. Only the view of the last jump is kept per group, because a later jump can merge the range of an earlier one into its own.
- If the backend answers that the range of a kept view is gone, for example because "Load older discussions" in the normal view merged it into the newest range, the frontend calls jump again and shows the view with the new range.
- A bar above the list reads "Showing up to {date}" with a link back to the normal view.
- At the end of the list, "Load older discussions" (spec 005) is shown unless the server's low watermark has been reached. It extends the range of the jump.
- When the day is before the oldest article on the server, the view shows no list. It states the date of the oldest article with a link to the date view of that day.

**Thread page**

- The thread's root at the top, replies nested below, ordered as in spec 006.
- Each nesting level is indented and marked with a vertical line. Indentation grows up to 6 levels; deeper replies are shown at the sixth level.
- Each article shows the author icon, author, time, the "new" marker if unread, and its text (spec 007).
- Clicking an article's line or its collapse button collapses the article and its replies to one line: author and "N replies hidden". Collapse state is not remembered.
- The page opens at the top. Back returns to the list at the same scroll position (UI concept).
- A stub shows "Not available locally" with a gray author icon; its content is fetched when it scrolls into view (spec 007).
- A crossposted article located only in other groups is marked with the group it was posted to (spec 006).
- The thread page header has "Mark thread as read" and "Ignore thread".
- Reply buttons are added by the posting specification; "Ignore author" in each article's menu by [spec 010](../010-ignoring/spec.md).

**Read state**

- Opening a thread does not mark it read; each article is marked read when its body is displayed (spec 007).
- "Mark all as read" in the group header works as in spec 004.
- "Mark thread as read" marks every unread non-stub article of the thread located in the group as read, without confirmation. Crossposts are marked read in all groups, as for "mark all as read". There is no undo.
- Articles stored by load older and jump to date arrive read (ADR 0022).

**Updates**

- When a synchronization of the group finishes while the list is open, the list is not reordered. A "N new discussions · show" button appears at the top; clicking it reloads the list and scrolls to the top.

**Empty states**

- During the group's first synchronization: "First synchronization running…" with a progress indicator.
- A synchronized group without articles: "No discussions yet".

## API contracts

All endpoints require a session ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457).

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/groups/{id}/threads` | `sort` (`newest`, `activity`, `discussed`; default `activity`), `until` (ISO 8601 instant, date view), `rangeId` (required with `until`; from the jump result), `cursor`, `limit` (default 50, max 100) | `200` page of thread cards; `400` invalid parameter; `404` unknown or unsubscribed group |
| `GET /api/groups/{id}/threads/{articleId}` | | `200` thread; `404` unknown article or not part of a thread in the group |
| `POST /api/groups/{id}/threads/{articleId}/mark-read` | | `204`; `404` as above; `409` group not subscribed |

`sort` and `until` are mutually exclusive; with `until`, the list has the threads whose card article is dated before that instant, newest first.

**Page of thread cards**

```json
{
  "items": [
    {
      "rootId": 1201,
      "cardArticleId": 1201,
      "subject": "Records vs. Lombok",
      "from": "Jane Doe <jane@example.org>",
      "authorKey": "jane@example.org",
      "date": 1763450000000,
      "excerpt": "I have been comparing…",
      "messageCount": 14,
      "latestActivity": 1763460000000,
      "unreadCount": 3
    }
  ],
  "nextCursor": "opaque",
  "rangeId": 7,
  "lowWatermarkReached": false,
  "highWatermarkReached": true
}
```

`rangeId` is the synchronized range the list shows: the newest range of the group in the normal view, and the range of the jump in a date view. It is `null` when the group has no range yet. The frontend passes it to extend (spec 005) for "Load older discussions".

**Thread**

`{articleId}` can be any article of the thread; the response names the current root (spec 006).

```json
{
  "rootId": 1201,
  "articles": [
    {
      "id": 1201,
      "parentId": null,
      "depth": 0,
      "isStub": false,
      "subject": "Records vs. Lombok",
      "from": "Jane Doe <jane@example.org>",
      "authorKey": "jane@example.org",
      "date": 1763450000000,
      "read": true,
      "otherGroup": null
    }
  ]
}
```

`authorKey` on cards and articles is the stored author key ([spec 010](../010-ignoring/spec.md)), `null` for stubs; the frontend derives the author icon from it and does not parse `from` itself.

`articles` is in display order. `otherGroup` is the name of a group the article is located in when it is not located in this group.

## Technical design

- The thread list is computed by one query per page, grouping the group's located non-stub articles by `thread_root_id`; the cursor is keyset-based on the sort key and `rootId`. An index on `article.thread_root_id` supports it.
- The thread page returns the whole tree in one response; text is loaded per article (spec 007). If very large threads become slow to render, rendering only what is on screen can be added later.
- The frontend notices finished synchronizations through the subscription list it already polls (spec 004) and then shows the "new discussions" button instead of refetching the list.
- Relative times, indentation and collapsing are computed in the frontend.

## Data model changes

- Index on `article.thread_root_id`.

## Error handling

| Situation | Behavior |
|---|---|
| Group unsubscribed while open | `404`; the frontend returns to the discover view |
| Thread link to an article that no longer exists | `404`; "This discussion is no longer available" with a link to the group |
| Invalid `sort`, `from` or `cursor` | `400` |

## Security considerations

- Subjects, author names and excerpts are rendered as escaped text, as article text in spec 007.
- `cursor` is opaque to the frontend and validated by the backend; it carries no SQL.

## Testing requirements

Integration tests against a real SQLite database (ADR 0008):

- The thread list includes only threads with a non-stub article located in the group; counts and unread counts follow spec 006 and exclude crossposts located only elsewhere.
- Each sort order, including undated articles and ties; keyset paging returns every thread exactly once.
- Date view: only threads before the given instant, newest first, only threads of the given range, watermark flags; going back from a thread does not call jump again; a range that is gone leads to a new jump.
- Thread endpoint: any article resolves to the root; depth, order and `otherGroup` are correct.
- Mark thread as read marks exactly the thread's unread non-stub articles located in the group, and only the thread's.

Frontend component tests:

- Card with and without excerpt and unread articles; relative and absolute times; mark thread as read clears "● N new".
- Indentation capped at 6 levels; collapsing and expanding; stub and crosspost display.
- The list is not reordered after a synchronization until "show" is clicked.

## Implementation tasks

1. Flyway migration for the `thread_root_id` index.
2. Thread list query with sorting, date view and keyset paging.
3. Thread list, thread and mark thread as read endpoints.
4. Frontend: group view with sort tabs, cards with mark thread as read, infinite scrolling, load older, date view bar and new discussions button.
5. Frontend: thread page with nesting, collapsing, stubs and crossposts.
