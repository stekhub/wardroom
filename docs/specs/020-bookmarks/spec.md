# 020. Bookmarks

- Status: Proposed
- Date: 2026-11-23
- Related: [ADR 0023](../../architecture/decisions/0023-cache-size-limit.md), [spec 004](../004-subscriptions/spec.md), [spec 007](../007-article-reading/spec.md), [spec 014](../014-next-unread-and-mark-as-unread/spec.md)
- Changes when accepted: "Cleanup job" of ADR 0023 gets an exception, which needs a new ADR; the confirmation of unsubscribing in spec 004 and the UI concept

## Scope

Bookmarking articles, a page that lists them, and keeping bookmarked articles when the cache is cleaned. Step 28 of the [implementation plan](../../implementation-plan.md), after 1.0.

## Decisions

Decided by the owner on 2026-11-23:

1. The owner bookmarks an article. A thread is bookmarked through its first article; there are no thread bookmarks. This replaces "keeping articles".
2. "Bookmarks" in the sidebar opens a page with the bookmarked articles, newest bookmark first. Each shows the group, subject, author, date and an excerpt, and opens its thread at that article.
3. A bookmarked article is kept when the cache is cleaned.
4. Unsubscribing removes the bookmarks of the group with its articles; the confirmation says so.

## Details added by the AI assistant

Open to a veto by the owner:

1. The bookmark button is in the header of each article on the thread page (Lucide `bookmark`, filled when set), not hidden in the menu, because it is used often.
2. Bookmarking an article whose text is not cached fetches it, so it can be kept.
3. Bookmarks of ignored authors and threads stay on the page; a bookmark is an explicit choice.
4. A bookmarked article whose location expired on the server stays readable from the cache.
5. The settings page shows how much of the cache bookmarked articles use, because they can keep the cache above its limit.

## User stories

- As the owner, I bookmark an article I want to find again.
- As the owner, a bookmarked article stays, even when the cache is cleaned.

## Functional requirements

- One click sets or removes the bookmark, with a short toast.
- "Bookmarks" is in the sidebar below "Discover groups". The page `/bookmarks` lists the bookmarks, newest first, 50 at a time, loading more at the end.
- Each entry shows the subject, the author with the author icon, the group, the date, the excerpt (first 200 characters, as on the cards) and the unread state, and a button to remove the bookmark.
- A click opens the thread at the article (`?article=`, spec 014).
- The cache cleanup never empties the content of a bookmarked article. The settings page shows how much of the cache bookmarked articles use, so the owner understands why the cache stays above its limit if it does.
- Unsubscribing: the confirmation adds "This also removes N bookmarks" when the group has bookmarked articles that are not located in another subscribed group.
- A crossposted article stays bookmarked as long as one of its groups is subscribed.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `PUT`, `DELETE /api/articles/{id}/bookmark` | | `204`; `404` unknown article |
| `GET /api/bookmarks` | `cursor`, `limit` (default 50, max 100) | `200` page of bookmarks |

- A bookmark entry has the fields of a search result ([spec 016](../016-full-text-search/spec.md)) without `excerpt` parts: `excerpt` is plain text or `null`, plus `bookmarkedAt`.
- The articles of the thread get a field `bookmarked`.
- The subscriptions get `bookmarkCount`, the bookmarks that unsubscribing would remove.
- `GET /api/settings` reports the cache use of bookmarked articles next to the current use.

## Technical design

- A new table `bookmark` in the `articles` module; the row is deleted with its article.
- `CacheCleanup` leaves out bookmarked articles, as it leaves out own articles that wait for their location.
- The page reads bookmarks with their article and the first subscribed group by name where the article is located.

## Data model changes

- New table `bookmark` with `article_id` (primary key, references `article`, deleted with it) and `created_at`.

## Error handling

| Situation | Behavior |
|---|---|
| Fetching the content of a new bookmark fails | The bookmark is set; the content is fetched when the article is opened, as today |
| The bookmarked articles alone exceed the cache limit | The cleanup empties everything else and stops; the settings page shows the use of bookmarks |

## Security considerations

None beyond spec 007: subjects and excerpts are drawn as text.

## Testing requirements

- Setting and removing a bookmark; the page in the order of bookmarking, with paging.
- The cache cleanup keeps bookmarked content.
- Unsubscribing removes the bookmarks of the group, not those of crossposts still located elsewhere; `bookmarkCount` is right.
- Removing the server removes all bookmarks.
- Frontend: the button, the page, the confirmation with the number.

## Implementation tasks

1. ADR for the exception in the cache cleanup.
2. Migration and endpoints; cleanup exception; `bookmarkCount`.
3. Button on the thread page; page and sidebar entry.
4. Confirmation of unsubscribing; cache use of bookmarks in the settings.
5. Update spec 004, the UI concept and the user guide.
