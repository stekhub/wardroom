# 014. Next unread, mark as unread and keyboard shortcuts

- Status: Accepted
- Date: 2026-11-23
- Related: [spec 007](../007-article-reading/spec.md), [spec 008](../008-discussion-view/spec.md), [spec 010](../010-ignoring/spec.md), [spec 013](../013-dark-theme/spec.md)
- Changes when accepted: "Read state" in spec 007 ("There is no mark as unread in the MVP"); "The page opens at the top" in spec 008

## Scope

The key `N` for the next unread article, a few more keys for reading without the mouse, and marking an article as unread again. Step 20 of the [implementation plan](../../implementation-plan.md).

## Decisions

Decided by the owner on 2026-11-23:

1. `N` opens the next unread article, across threads in the current sort order. At the end of a group, it goes to the next group with unread articles.
2. An article opened with `N` is marked as read at once.
3. Mark as unread is part of this step.
4. `N` does nothing while the owner types in a field.
5. A setting "Keyboard shortcuts", on by default and stored in the browser, turns all keys off (WCAG 2.2 criterion 2.1.4).

Added by the owner on 2026-11-24, with the letters proposed by the AI assistant:

6. `Space` scrolls the article and, at its end, does what `N` does.
7. `J` and `K` go to the next and the previous article.
8. `R` replies to the current article.
9. `C` marks the current thread as read.
10. `?` shows the list of keys.

## Details added by the AI assistant

Open to a veto by the owner:

1. At the end of a group, `N` opens the group view of the next subscribed group with unread articles, in the order of the sidebar, starting again at the top after the last one. The next `N` opens its first unread thread. The owner sees where they are before reading on.
2. When no group has unread articles, a toast says "No unread articles".
3. The thread page can be opened at one article: the address `?article={id}` scrolls to it and highlights it briefly. `N`, and later the search results and bookmarks, use it.
4. An article marked as unread is not marked as read again while the thread page stays open, even though its text is still displayed. It is marked read again when the thread is opened again or `N` reaches it.
5. "Mark as unread" is in the menu of each article on the thread page (Lucide `mail`), only for articles that are read.

## User stories

- As the owner, I press `N` and read all new articles of my groups one after the other, without the mouse.
- As the owner, I read, reply and catch up with a few keys, as in the newsreaders I know.
- As the owner, I mark an article as unread, so I come back to it later.

## Functional requirements

**Next unread**

- `N` works in the group view and on the thread page.
- In the group view, `N` opens the first thread in the current sort order that has an unread article, at its first unread article.
- On the thread page, `N` scrolls to the next unread article after the one at the top of the screen, in the order of the page. When there is none, it opens the next thread with an unread article after the current one, in the sort order of the list the owner came from (default: latest activity).
- In the date view, `N` stays within the view: it only opens threads the view shows.
- Threads and authors the owner ignores are skipped (spec 010).
- At the end of a group: details 1 and 2.
- The article `N` reaches is marked read at once.
- `N` does nothing while focus is in a text field, a text area or an open dialog, or when a modifier key is held.

**Other keys**

- The current article is the one `N`, `J` or `K` reached last; before that, the first article at the top of the screen.
- `Space` on the thread page scrolls down by one screen. When the end of the page is visible, it does what `N` does. In the group view, it does what `N` does.
- `J` and `K` on the thread page move to the next and the previous article in the order of the page and scroll to it; it is marked read, as with `N`. In the group view, they move the focus to the next and the previous card, and Enter opens it.
- `R` opens the reply editor below the current article, where replying is possible ([spec 009](../009-article-posting/spec.md)). Otherwise it does nothing.
- `C` marks the current thread as read, as "Mark thread as read" does ([spec 008](../008-discussion-view/spec.md)), and does what `N` does. In the group view, it applies to the card with the focus.
- `?` opens a dialog with the list of keys. Escape closes it.
- All keys follow the rules of `N`: nothing happens in a field, in an open dialog or with a modifier key held, and the setting turns them off.

**Mark as unread**

- "Mark as unread" removes the read state of the article. The unread counts of the sidebar and the thread list show it again.
- The read state belongs to the article, so a crossposted article is unread again in all groups.

**Setting**

- "Keyboard shortcuts" in the settings section "Preferences" ([spec 013](../013-dark-theme/spec.md)), on by default. The section lists the keys, as the dialog of `?` does.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/groups/{id}/threads/next-unread` | `sort` (as for the thread list), `after` (article ID of the current thread, optional), `until` and `rangeId` (date view, optional) | `200 { "threadArticleId" }`: the next thread with an unread article; `204` none in this group; `400` invalid parameter; `404` unknown or unsubscribed group |
| `DELETE /api/articles/{id}/read` | | `204`, also when the article was unread; `404` unknown article |

- `threadArticleId` is the ID the thread page is opened with, as in the thread cards.

## Technical design

- `next-unread` uses the same ordering and filters as the thread list (`ThreadListRepository`, `IgnoreFilters`) and returns the first thread after `after` that has an unread non-stub article located in the group. The client cannot do this alone, because the list is loaded page by page.
- The thread page finds the next unread article in the thread it has loaded; it asks the backend only for the next thread.
- The next group comes from the subscriptions and their unread counts the frontend already has (`useSubscriptions`).
- The read state is a row in `read_state` (spec 007); mark as unread deletes it. Afterwards the frontend updates the loaded lists as it does after reading (`threadState.ts`) and asks for the unread counts again.
- The setting is stored in `localStorage` under `wardroom.keyboardShortcuts`, read as spec 013 reads the theme.

## Data model changes

None.

## Error handling

| Situation | Behavior |
|---|---|
| The group was unsubscribed meanwhile | `404`; `N` goes to the next group |
| The article was deleted meanwhile | `DELETE` answers `404`; the frontend reloads the thread |

## Security considerations

- No new data. The key handler ignores input in fields, so typed text never triggers it.

## Testing requirements

- `next-unread`: all three sort orders, `after` in the middle and at the end, the date view, ignored threads and authors skipped, `204` when nothing is unread.
- `DELETE /api/articles/{id}/read` removes the read state, changes the unread counts and answers `204` for an unread article.
- Frontend: `N` in the group view and on the thread page; `Space` scrolling and then going on as `N`; `J` and `K` on the thread page and in the group view; `R` opening the editor, and doing nothing where replying is not possible; `C` marking the thread read; `?` and Escape; to the next thread; to the next group; the toast when nothing is unread; nothing happens in a field or with the setting off; an article marked as unread is not marked read again while the page is open; `?article=` scrolls to the article.

## Implementation tasks

1. `next-unread` endpoint and `DELETE` of the read state.
2. Thread page address `?article=`.
3. Key handler with all keys, the dialog of `?`, and the setting in "Preferences".
4. "Mark as unread" in the article menu.
5. Update specs 007 and 008 and the user guide.
