# 021. Newest articles

- Status: Proposed
- Date: 2026-11-23
- Related: [spec 008](../008-discussion-view/spec.md), [spec 010](../010-ignoring/spec.md), [spec 014](../014-next-unread-and-mark-as-unread/spec.md)
- Changes when accepted: "Views" in the UI concept; the start page

## Scope

One page with the newest articles of all subscribed groups, which becomes the start page. Step 30 of the [implementation plan](../../implementation-plan.md), after 1.0.

## Decisions

Decided by the owner on 2026-11-23:

1. One page with the newest articles of all subscribed groups, as single articles that open their thread.
2. Read and unread articles are shown, unread ones marked; ignored ones are left out.
3. The page is the start page after login.

## Details added by the AI assistant

Open to a veto by the owner:

1. "Newest" is by the `Date` of the article. Articles without a date, or dated more than a day in the future, are left out, so a wrong date cannot keep an article at the top.
2. Articles stored by "load older" and jump to date are part of the page only by their date, so they appear far down.
3. Without subscriptions, the start page stays the discover view.
4. "Newest" is in the sidebar above "Discover groups".

## User stories

- As the owner, I see at a glance what was written in my groups since I last looked.

## Functional requirements

- The page `/newest` lists articles of the subscribed groups, newest first, 50 at a time, loading more at the end.
- Each entry shows the subject, the author with the author icon, the group, the date, the excerpt if cached and the unread state; unread entries are bold.
- A click opens the thread at the article (`?article=`, spec 014). Opening the page marks nothing as read.
- Stubs, ignored authors and threads, and own articles that wait for their location are left out. A crosspost appears once, with the first subscribed group by name where it is located.
- `/` opens `/newest` when a group is subscribed, else `/discover`.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/newest` | `cursor`, `limit` (default 50, max 100) | `200` page of articles |

- An entry has the fields of a bookmark entry ([spec 020](../020-bookmarks/spec.md)) without `bookmarkedAt`.

## Technical design

- The query uses the index on `article.date`, joins the locations of subscribed groups and applies `IgnoreFilters`. The cursor is the date and the article ID.

## Data model changes

None; an index on `article.date` for all articles is added if the existing partial index does not cover the query.

## Error handling

None beyond the usual: an empty page says that the subscribed groups have no articles yet.

## Security considerations

None beyond spec 007: subjects and excerpts are drawn as text.

## Testing requirements

- Order by date; undated and far-future articles left out; paging.
- Ignored authors and threads, stubs, unsubscribed groups and own waiting articles left out; a crosspost appears once.
- `/` leads to `/newest` with subscriptions and to `/discover` without.
- Frontend: the page, the click to the article, loading more.

## Implementation tasks

1. `GET /api/newest` with the index if needed.
2. Page, sidebar entry and start page.
3. Update the UI concept and the user guide.
