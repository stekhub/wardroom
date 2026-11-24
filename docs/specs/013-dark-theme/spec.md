# 013. Dark theme

- Status: Accepted
- Date: 2026-11-23
- Related: [UI concept](../../product/ui-concept.md), [ADR 0007](../../architecture/decisions/0007-frontend-stack.md)
- Changes when accepted: "Visual design" and "Not in the MVP" in the UI concept; the rule "Light theme only in the MVP" in `CLAUDE.md`

## Scope

A dark theme next to the light one, and the choice between Light, Dark and System. Step 19 of the [implementation plan](../../implementation-plan.md).

## Decisions

Decided by the owner on 2026-11-23:

1. The choice is Light, Dark or System. The default is System, which follows the setting of the operating system.
2. The choice is stored in the browser, so each device has its own.
3. The logo follows the theme.

## Details added by the AI assistant

Open to a veto by the owner:

1. The choice is in a new settings section "Preferences" (Lucide `sliders-horizontal`), which says that its settings are stored in this browser. It takes effect at once, without a save button, because nothing is sent to the server. [Spec 014](../014-next-unread-and-mark-as-unread/spec.md) adds the keyboard setting to the same section.
2. The theme is set before the page is drawn, so a dark page does not flash white while it loads. This is done by a small script file loaded in the page header; an inline script is not allowed by the Content Security Policy.
3. The icon in the browser tab follows the setting of the operating system, not the choice in Wardroom, because browsers draw it next to their own tabs, which follow the operating system. The icon is an SVG with a variant for each.
4. With System, the page follows a change of the operating system setting at once, without a reload.

## User stories

- As the owner, I read in a dark theme in the evening.
- As the owner, Wardroom follows the light or dark setting of my computer without me choosing anything.
- As the owner, my phone can use another theme than my computer.

## Functional requirements

- The section "Preferences" offers "Theme" with Light, Dark and System; System is chosen until the owner chooses something else.
- The choice applies to every page, including the login page and the first-run wizard.
- All colors come from the design tokens, defined once for each theme. Text, icons, borders and focus rings meet the contrast of WCAG 2.2 AA in both themes.
- Quotes, signatures and links in article text (spec 007), the group icons and the author icons stay readable in both themes. The author and group icon colors keep their rules; only the colors around them change.
- The logo uses the light variant in the light theme and the dark variant from `assets/logo/` in the dark theme.
- Form controls and scrollbars follow the theme (`color-scheme`).

## API contracts

None. The choice never leaves the browser.

## Technical design

- The theme is a class `dark` on the root element, as shadcn/ui expects; the dark tokens are defined for it in `src/index.css`.
- The choice is stored in `localStorage` under `wardroom.theme` (`light`, `dark` or `system`). Reading and writing are wrapped, so a browser that blocks storage falls back to System without an error.
- A script file `theme.js`, loaded in the page header before the stylesheet is applied, reads the choice and sets the class. The React code keeps it in sync afterwards and listens to `prefers-color-scheme` while System is chosen.
- The favicon is an SVG with a `prefers-color-scheme` media query inside.

## Data model changes

None.

## Error handling

| Situation | Behavior |
|---|---|
| The browser blocks storage | System is used; the choice applies until the page is reloaded |
| A stored value is unknown | System is used |

## Security considerations

- No new data leaves the browser.
- `theme.js` is served from Wardroom itself, so the Content Security Policy stays unchanged.

## Testing requirements

- Choosing Light, Dark and System sets the class on the root element and stores the choice.
- With System, a change of `prefers-color-scheme` changes the theme.
- A stored choice is applied after a reload; an unknown value or blocked storage falls back to System.
- The logo shows the variant of the theme.
- The theme script sets the class before the application starts.

## Implementation tasks

1. Dark tokens in `src/index.css`; check every component and view for colors that do not come from tokens.
2. `theme.js` and the theme state in the frontend.
3. Settings section "Preferences" with the theme choice.
4. Logo and favicon variants.
5. Update the UI concept, `CLAUDE.md` and the user guide.
