# Accessibility checklist

Wardroom aims at WCAG 2.2 level AA ([spec 015](specs/015-accessibility-and-mobile-fixes/spec.md)). This checklist is gone through by hand for every step that changes the frontend. There are no automated accessibility checks.

Check each point in the light and in the dark theme. Go through the pages in this order: login, first-run wizard, discover, group view, thread page, article editor, each settings section.

## Keyboard

- Every action can be reached with Tab and started with Enter or Space, in a sensible order.
- The first Tab on a page reaches "Skip to content". Enter moves to the main area.
- The focus ring is always visible.
- A dialog keeps the focus inside, closes with Escape and returns the focus to where it was.
- There is no keyboard trap, also not in the article editor.
- The single keys for reading ([spec 014](specs/014-next-unread-and-mark-as-unread/spec.md)) do nothing in a field, and can be turned off.

## Screen reader

Tested with NVDA and Firefox or Chrome; see the [test guide](#test-guide-for-nvda).

- Every page has one main heading and landmarks for the sidebar and the main area.
- After a change of page, the main heading of the new page is announced. After a change of the settings section, the heading of the section is.
- The headings list shows the structure of the page.
- Every button, field and icon has a name. Icon buttons say what they do, not what they show.
- Unread counts, "new" markers and status icons are read out, not only shown as color or symbol.
- Toasts and the start and end of a synchronization are announced, without moving the focus.
- On the thread page, every article says its level: "Level 1" for the first article, "Level 2" for a reply to it.

## Zoom and reflow

- At 200 percent, nothing is cut off or overlaps.
- At 400 percent, and at a width of 320 pixels, every page works without scrolling sideways. Article text with long lines may scroll inside its own box.
- Larger text spacing does not cut off text (WCAG 1.4.12): line height 1.5, paragraph spacing 2, letter spacing 0.12 and word spacing 0.16 times the font size.

## Narrow screens

Below 768 pixels the mobile layout applies. Check every page at 375 and at 320 pixels wide.

- The sidebar is a menu that opens with the button at the top. It closes after a group, "Discover groups" or the settings were chosen.
- Buttons with an icon and a label in the group header, the thread page header and the paging row of the group list show only their icon. Their tooltip and their name for screen readers say what they do.
- Every button and link is at least 24 by 24 pixels (WCAG 2.5.8).

## Color and motion

- Text has a contrast of at least 4.5:1, large text and icons that carry information 3:1.
- The outline of fields, checkboxes and radio buttons, and the focus ring, have 3:1 against what is around them.
- Information is never given by color alone.
- Animations stop when the device asks for reduced motion.

## Test guide for NVDA

1. Install NVDA from its website and start it. Open Wardroom in Firefox or Chrome.
2. The keys you need:

   | Key | What it does |
   |---|---|
   | `Insert+Space` | Switches between reading the page and typing into it |
   | `H` | Next heading |
   | `D` | Next landmark |
   | `Tab` | Next button, link or field |
   | `Insert+F7` | Lists the headings, links and landmarks of the page |
   | `Insert+Down` | Reads from the current position |
   | `Ctrl` | Stops reading |

3. Go through the list "Screen reader" above, page by page.
4. Wardroom's own single keys (`N`, `J`, `K` and the others) are used by NVDA for its own navigation while it reads a page. Press `Insert+Space` first to use them, or turn them off in the settings, section "Preferences".

## Known limits

Findings that are not fixed, with the reason:

- **Buttons of the article editor.** "Send" and "Cancel" have no icon and stay as text on a narrow screen. They fit at 320 pixels.
- **Quote bars, the circles of group icons.** They use fixed colors outside the theme. They are decoration: the quote level is also shown by the indentation and the group by its name.

## Result of the last check

| Step | Date | Checked | Result |
|---|---|---|---|
| 21 | 2026-11-25 | Keyboard, zoom and reflow, narrow screens, color: by the AI assistant in a browser, in both themes | See the notes of step 21 in the [implementation plan](implementation-plan.md#notes) |
| 21 | open | Screen reader with NVDA: by the owner | Not done yet |
