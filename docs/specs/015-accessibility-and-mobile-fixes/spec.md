# 015. Accessibility and mobile fixes

- Status: Accepted
- Date: 2026-11-23
- Related: [UI concept](../../product/ui-concept.md), [spec 013](../013-dark-theme/spec.md), [spec 014](../014-next-unread-and-mark-as-unread/spec.md)
- Changes when accepted: "Not in the MVP" in the UI concept (mobile layout)

## Scope

A systematic check of the whole application against WCAG 2.2 AA, the fixes it finds, and the fixes for narrow screens. Step 21 of the [implementation plan](../../implementation-plan.md).

## Decisions

Decided by the owner on 2026-11-23:

1. The check covers WCAG 2.2 AA, with the keyboard alone, with the screen reader NVDA, and zoomed to 200 and 400 percent, in both themes.
2. No automated accessibility checks.
3. Mobile: the menu closes after a group or another page was chosen; buttons that take too much space show only their icon below 768 pixels, with a tooltip and a label; every page is checked at the width of a phone.
4. Below 768 pixels the mobile layout applies.

## Details added by the AI assistant

Open to a veto by the owner:

1. The checklist below is kept as `docs/accessibility-checklist.md`, so later steps are checked the same way. The implementation plan says so for each frontend step.
2. A link "Skip to content" is the first element of every page; it appears when it gets focus.
3. After a change of page, focus moves to the main heading of the new page, so a screen reader announces it.
4. The nesting of replies on the thread page is announced: each reply says its level ("level 3").
5. Toasts and the synchronization status are announced as status messages, without moving focus.
6. Touch targets are at least 24 by 24 pixels (WCAG 2.2 criterion 2.5.8).

## User stories

- As the owner, I use all of Wardroom with the keyboard alone.
- As an owner who uses a screen reader, I find the groups, threads and articles and know where I am.
- As the owner, Wardroom works on my phone without zooming or scrolling sideways.

## Functional requirements

**Checklist** (in `docs/accessibility-checklist.md`)

Keyboard:
- Every action can be reached with Tab and started with Enter or Space, in a sensible order.
- The focus ring is always visible, in both themes.
- Dialogs keep focus inside, close with Escape, and return focus to where it was.
- No keyboard trap anywhere, also not in the article editor.

Screen reader (NVDA with Firefox or Chrome):
- Every page has one main heading and landmarks for the sidebar and the main area.
- The headings list (NVDA key `Insert+F7`) shows the structure of the page.
- Every button, field and icon has a name. Icon buttons say what they do, not what they show.
- Unread counts, "new" markers and status icons are read out, not only shown as color or symbol.
- Toasts and status changes are announced.
- The thread page says the level of each reply.

Zoom and reflow:
- At 200 percent, nothing is cut off or overlaps.
- At 400 percent, and at a width of 320 pixels, every page works without scrolling sideways; article text with long lines may scroll inside its own box.
- Larger text spacing does not cut off text (WCAG 1.4.12).

Color:
- Contrast of WCAG 2.2 AA for text, icons, borders and focus rings in both themes.
- Information is never given by color alone.
- Animations follow the setting for reduced motion (UI concept).

**Mobile fixes**

- The sidebar menu closes after a group, "Discover groups", the settings or another entry was chosen.
- Below 768 pixels, buttons with a label in the group header, the thread page header and the article editor show only their icon, with a tooltip and an accessible label.
- Every page is checked at 375 and 320 pixels wide in both themes; what does not fit is fixed.

**Test guide for NVDA** (part of the checklist)

- Install NVDA from its website, start it, and open Wardroom in Firefox or Chrome.
- `Insert+Space` switches between reading and interacting; `H` jumps to the next heading, `D` to the next landmark, `Tab` to the next control; `Insert+F7` lists headings, links and landmarks; `Insert+Down` reads from the current position.
- Go through the checklist page by page: login, wizard, discover, group view, thread page, article editor, each settings section.

## API contracts

None.

## Technical design

- The mobile menu is the sidebar component of shadcn/ui; it is closed on every change of route.
- Icon-only buttons on narrow screens use the same component with the label hidden visually, so the accessible name stays.
- The level of a reply is given with the tree semantics of a list (`aria-level`), or a text hidden visually.
- Findings that are not fixed in the step, if any, are listed in the checklist with the reason.

## Data model changes

None.

## Error handling

None.

## Security considerations

None.

## Testing requirements

- The menu closes after choosing an entry on a narrow screen.
- Icon-only buttons have an accessible name.
- "Skip to content" moves focus to the main area; after a change of page, focus is on the main heading.
- Replies on the thread page carry their level.
- The manual check of the checklist is done once in the step, and its result is noted in the pull request.

## Implementation tasks

1. Write `docs/accessibility-checklist.md` with the test guide.
2. Go through the checklist and fix the findings.
3. Mobile fixes: closing menu, icon-only buttons, the pages at phone width.
4. Update the UI concept and the user guide.
