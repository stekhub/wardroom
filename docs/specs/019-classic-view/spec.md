# 019. Classic view

- Status: Proposed
- Date: 2026-11-23
- Related: [spec 008](../008-discussion-view/spec.md), [spec 013](../013-dark-theme/spec.md), [spec 014](../014-next-unread-and-mark-as-unread/spec.md), [spec 015](../015-accessibility-and-mobile-fixes/spec.md)
- Changes when accepted: "Views" in the UI concept

## Scope

A second way to show the groups: a tree of the threads at the top and the selected article below it. Step 27 of the [implementation plan](../../implementation-plan.md), after 1.0.

## Decisions

Decided by the owner on 2026-11-23:

1. The subscribed groups on the left as today, a compact tree of the threads at the top, and the selected article below it.
2. The choice applies to all groups: the whole interface switches.
3. The tree has a toggle to open or close a thread, the subject indented by reply depth, the author and the date. Unread articles are bold, with a dot. It is in thread order, newest thread first; no sorting or resizing of columns.
4. Only on wide screens. Below 768 pixels the card view is shown, whatever the choice; the setting says so.
5. `N` and mark as unread work in it, and it is checked for accessibility as in spec 015.

## Details added by the AI assistant

Open to a veto by the owner:

1. The choice "View: Cards or Classic" is in the settings section "Preferences" and is stored in the browser, like the theme, because it depends on the screen.
2. The sort tabs, the date view, "load older" and the header of the group stay as in the card view. "Newest thread first" means the order of the chosen tab.
3. The tree and the article each scroll on their own. The tree takes 40 percent of the height; the split cannot be moved.
4. Threads are closed at first and open with their toggle; the row of a closed thread shows the card article and the number of replies.
5. The tree follows the keyboard pattern of a tree: up and down move the selection, right opens a thread, left closes it, Enter shows the article. This is not a shortcut but the expected behavior of the control (WAI-ARIA tree grid).
6. The article pane shows the same header, menu and text as on the thread page, with reply, ignore, bookmark and mark as unread.
7. A thread page address (`/groups/{id}/threads/{articleId}?article=`) opens the group in the classic view, with the thread open and the article selected, so links from search and bookmarks work in both views.

## User stories

- As a long-time Usenet reader, I read a group in the three-pane layout I know.
- As the owner, I move through a thread with the arrow keys and read article after article.

## Functional requirements

- Selecting a row shows its article below. The article is marked read when its text is displayed, as on the thread page (spec 007).
- Opening a thread loads it with the existing thread endpoint; its replies appear nested below its row.
- `N` selects the next unread article, opening its thread, and goes on to the next thread and group as in spec 014.
- Unread rows are bold and have a dot; the dot has a text for screen readers.
- Ignored threads and authors are left out as in the card view.
- A narrow screen always shows the card view; the setting says "Only on wide screens".

## API contracts

None. The tree uses the thread list and the thread endpoint of spec 008.

## Technical design

- The view is a new component next to `ThreadList` and `ThreadArticles`, using the same queries (`threadState.ts`), so read state and ignoring behave the same.
- The tree is a WAI-ARIA tree grid; rows are drawn only when visible, so long threads stay fast.
- The setting is stored in `localStorage` under `wardroom.view`.

## Data model changes

None.

## Error handling

| Situation | Behavior |
|---|---|
| A thread cannot be loaded | The row shows the error with "Try again", as the thread page does |
| The selected article was deleted meanwhile | The selection moves to its thread |

## Security considerations

None beyond spec 007: article text and subjects are drawn as text.

## Testing requirements

- Switching the view changes all groups; a narrow screen shows the cards.
- Opening and closing threads; selecting shows the article and marks it read when displayed.
- Keyboard: up, down, right, left and Enter.
- `N` and mark as unread in the classic view.
- A thread page address opens the classic view with the article selected.
- The accessibility checklist of spec 015 for the new view.

## Implementation tasks

1. Setting "View" in "Preferences".
2. Tree grid with rows, toggles and keyboard.
3. Article pane.
4. `N`, mark as unread and thread page addresses in the classic view.
5. Accessibility check; update the UI concept and the user guide.
