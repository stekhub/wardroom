# 010. Ignoring authors and threads

- Status: Accepted
- Date: 2026-11-18
- Related: [UI concept](../../product/ui-concept.md), [spec 004](../004-subscriptions/spec.md), [spec 006](../006-threading/spec.md), [spec 008](../008-discussion-view/spec.md)

## Scope

A basic killfile: ignoring an author or a thread, how ignored articles are shown and counted, and managing the rules. Patterns, scoring and per-group rules are not part of the MVP.

## User stories

- As the owner, I ignore an author whose posts I do not want to read, in every group.
- As the owner, I ignore a thread I am not interested in, including its future replies.
- As the owner, I see and remove my ignore rules, and everything comes back as it was.

## Functional requirements

**Ignoring**

- "Ignore author" is in each article's menu on the thread page. It applies to the author of that article.
- "Ignore thread" is on each thread card and in the thread page header.
- Both take effect at once and show a toast with "Undo", which removes the rule again.
- Rules apply in all groups.

**Ignored threads**

- An ignored thread is not shown in the thread list, including when new replies arrive.
- Its articles do not count as unread.
- Opening an ignored thread through a link still shows it, with a notice "You ignore this thread" and a button to stop ignoring it.

**Ignored authors**

- On the thread page, an article by an ignored author is collapsed to one line, "Ignored author · {name}", in its place; replies by other authors stay visible below it. The line can be expanded for this page view; expanding does not mark the article read.
- A thread whose card article ([spec 006](../006-threading/spec.md)) is by an ignored author is not shown in the thread list.
- Articles by ignored authors do not count as unread. Neither do the other articles of a thread that is hidden for this reason.

**A filter, not a change**

- Ignoring deletes nothing and marks nothing read. Removing a rule shows everything exactly as before, including unread state.
- "Mark all as read" and "Mark thread as read" still mark ignored articles read, as before.

**Managing rules**

- Settings section "Ignored" (Lucide `eye-off`) lists all rules, authors and threads separately, each with its label, the date it was added and a remove button.

## Matching

- **Author:** the author key, as used for author icons ([UI concept](../../product/ui-concept.md#author-icons)): the email address from `From`, lowercased and trimmed; without an address, the display name. The backend computes it when an article is stored.
- **Thread:** the `Message-ID` of the thread's root when the rule was created. A thread is ignored if any of its articles has that `Message-ID`. When the thread's root changes later, for example because a stub is filled in, the thread stays ignored.

## API contracts

All endpoints require a session and the CSRF token for state-changing requests ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457).

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/ignore-rules` | | `200` list of rules |
| `POST /api/ignore-rules` | `{ "kind": "AUTHOR" \| "THREAD", "articleId": 1234 }` | `201` rule (also if it already exists); `400`; `404` unknown article |
| `DELETE /api/ignore-rules/{id}` | | `204` (also if already removed) |

The backend derives the value and label from the article: for `AUTHOR` the author key and display name, for `THREAD` the root's `Message-ID` and subject.

**Rule**

```json
{ "id": 3, "kind": "AUTHOR", "label": "Jane Doe", "createdAt": 1795000000000 }
```

Thread articles of [spec 008](../008-discussion-view/spec.md) gain `ignoredAuthor` (boolean); the thread response gains `ignored` (boolean). Ignored threads are left out of the thread list; unread counts exclude ignored articles.

## Technical design

- The thread list, unread counts in the sidebar ([spec 004](../004-subscriptions/spec.md)) and "● N new" ([spec 008](../008-discussion-view/spec.md)) exclude ignored articles in their queries, through `article.author_key` and the rules table.
- An index on `article.author_key` keeps these queries fast.

## Data model changes

- New table `ignore_rule`: `kind` (`AUTHOR`, `THREAD`), `value`, `label`, `created_at`; unique on `(kind, value)`.
- New column `article.author_key`, indexed, computed on insert and when a stub is filled in; null for stubs.

## Error handling

| Situation | Behavior |
|---|---|
| Ignoring a stub | `400`: no known author; "Ignore thread" works through the thread root |
| Rule already exists | `201` with the existing rule |

## Security considerations

- Labels come from article headers and are rendered as escaped text.

## Testing requirements

Integration tests against a real SQLite database (ADR 0008):

- Author key: lowercased email, display name fallback, computed on insert and stub filling.
- Ignored threads are left out of the thread list and unread counts, also after new replies and after the root changes.
- Threads whose card article is by an ignored author are left out, and none of their articles count as unread.
- Ignored authors' articles are excluded from all unread counts and flagged in the thread response.
- Removing a rule restores lists and counts; no read state was written.
- Creating an existing rule returns it unchanged.

Frontend component tests:

- Ignore actions with toast and undo; collapsed ignored-author line and expanding it; notice on an ignored thread; settings section with removal.

## Implementation tasks

1. Flyway migration for `ignore_rule` and `article.author_key` with backfill.
2. Author key computation on insert and stub filling.
3. Rule endpoints.
4. Ignore filters in the thread list, thread, and unread count queries.
5. Frontend: ignore actions, toasts with undo, ignored-author line, ignored-thread notice, settings section "Ignored".
