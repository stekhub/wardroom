# UI Concept

The layout and interaction rules shared by all feature specifications. Each specification details its own views. Components and styling follow [ADR 0007](../architecture/decisions/0007-frontend-stack.md).

## Layout

```text
┌──────────────────────┬───────────────────────────────────────────────┐
│ Wardroom          ⚙  │  comp.lang.java.programmer          ★  ⟳  ✓✓  │
│ [🔍 Discover groups] │  [Newest] [Latest activity] [Most discussed]  │
│──────────────────────│ ┌───────────────────────────────────────────┐ │
│ comp.lang.java…   12 │ │ Subject of the thread                     │ │
│ de.comp.lang.ja…   3 │ │ Author · 2 h ago · 14 messages · ● 3 new  │ │
│ alt.folklore.c…  ⚠   │ │ Excerpt of the first article…             │ │
│ sci.space (removed)  │ └───────────────────────────────────────────┘ │
│                      │ ┌───────────────────────────────────────────┐ │
│                      │ │ …                                         │ │
└──────────────────────┴───────────────────────────────────────────────┘
```

**Sidebar** (always visible once logged in and a server is configured)

- Header: the logo, small, next to the application name, and a settings button.
- A "Discover groups" button at the top.
- The subscribed groups, alphabetically. Each entry shows the group icon, the group name (shortened with an ellipsis if too long, full name as tooltip) and its unread count.
- Status icons on an entry: synchronization running, last synchronization failed, removed from the server.

**Main area** shows one of the views below.

## Views

| View | Path | Content |
|---|---|---|
| Group | `/groups/{id}`, `/groups/{id}?until=2015-01-01` | Header with large group icon, group name, subscribe toggle, refresh, mark all as read, jump to date; sort tabs (newest, latest activity (default), most discussed); thread cards ([spec 008](../specs/008-discussion-view/spec.md)) |
| Thread | `/groups/{id}/threads/{articleId}` | The thread with nested replies, replacing the thread list. Back returns to the list at the same scroll position |
| Discover | `/discover` | Search box and paged group list; each row shows group icon, name, description, badges and the subscribe toggle |
| Settings | `/settings/{section}` | Section navigation and one section at a time; see [Settings page](#settings-page) |

- Every view has its own URL, so the browser's back button and bookmarks work.
- Without a session the login page is shown; without an account or without a server the first-run wizard ([spec 001](../specs/001-user-authentication/spec.md), [spec 002](../specs/002-nntp-server-configuration/spec.md)). These pages are shown without the sidebar, with the logo above the card.

## Visual design

Wardroom aims for a modern, clean look comparable to current web applications.

- **Components:** shadcn/ui throughout, with one set of design tokens (colors, radii, spacing) defined once in Tailwind. One accent color; everything else neutral.
- **Typography:** Inter, bundled with the application (no font is loaded from elsewhere). Article text uses a comfortable line length: the thread page's content column is at most 768 px wide.
- **Content width:** the discover view, the group view and the thread page show their content in one column of at most 768 px, centered in the area next to the sidebar. The column keeps its width when the sidebar is collapsed. The settings page is not centered.
- **Surfaces:** cards with rounded corners, a light border and a soft shadow on hover; generous whitespace.
- **Logo:** the files in `assets/logo/`. The application uses the light variant. The favicon is the logo centered on a square canvas.
- **Icons:** Lucide everywhere. Icon-only buttons have a tooltip and an accessible label.
- **Loading:** skeleton placeholders in the shape of the content instead of spinners.
- **Motion:** short transitions (150–200 ms) for collapsing, expanding and page changes; disabled when the browser asks for reduced motion.
- **Confirmations:** a short toast after actions the owner took, for example "Settings saved". Errors from background work stay status icons (see Feedback).
- **Accessibility:** WCAG 2.2 AA contrast, every action reachable by keyboard, visible focus rings.
- **Quick switcher:** `Ctrl+K` / `⌘K` opens a search over subscribed groups and the discover view.

## First-run wizard

A full-page, centered card without the sidebar, with a step indicator: **1 Account · 2 Server**.

1. **Account:** username and password, entered twice ([spec 001](../specs/001-user-authentication/spec.md)). Completing the step creates the account and logs in.
2. **Server:** the server form with connection test ([spec 002](../specs/002-nntp-server-configuration/spec.md)). This step cannot be skipped, because Wardroom is unusable without a server.

- After step 2, Wardroom opens the discover view, where the group list is being fetched.
- The account cannot be changed from step 2, because it already exists. If the browser is closed after step 1, the next login continues with step 2.

## Settings page

Opened with the settings button in the sidebar header. A section navigation on the left lists each section with its icon; the selected section is shown on the right and is part of the URL.

| Section | Icon (Lucide) | Content |
|---|---|---|
| Server | `server` | Server form, connection test, removal ([spec 002](../specs/002-nntp-server-configuration/spec.md)) |
| Synchronization | `refresh-cw` | Intervals, depth, cache size and current use ([spec 005](../specs/005-article-synchronization/spec.md)) |
| Posting identity | `id-card` | Display name, email address, signature ([spec 009](../specs/009-article-posting/spec.md)) |
| Ignored | `eye-off` | Ignored authors and threads ([spec 010](../specs/010-ignoring/spec.md)) |
| Account | `key-round` | Password change ([spec 001](../specs/001-user-authentication/spec.md)) |
| About | `info` | Version, license, link to the source code ([ADR 0011](../architecture/decisions/0011-licensing.md)) |
| Log out | `log-out` | An action at the end of the navigation, not a section |

- Each section has its own save button, enabled once something has changed; saving shows a toast.
- Sections added by later specifications appear in this navigation.

## Group icons

Every group has a round icon derived from its name and description: small in the sidebar and discover rows, large in the group header. The rules and the keyword table are in [Group Icons](group-icons.md).

## Author icons

Usenet has no profile pictures. Every author gets a round icon instead: the Lucide `user` symbol on a colored circle, with both colors derived from the author, so the same person always looks the same in every group and thread.

- **Key:** the email address from `From`, lowercased and trimmed; without an address, the display name. The backend computes it and returns it with each card and article ([spec 008](../specs/008-discussion-view/spec.md)).
- **Hash:** 32-bit FNV-1a over the key's UTF-8 bytes, as for group icons.
- **Circle color:** hue `hash mod 360`, saturation 55 %, lightness 42 %.
- **Symbol color:** hue `(hash >>> 16) mod 360`, saturation 70 %, lightness 90 %.
- The fixed lightness values keep the symbol readable on every circle; two authors may look alike, which is acceptable because the name is always shown next to the icon.
- **Sizes:** 24 px on thread cards, 32 px next to each article on the thread page.
- **Stubs** have no known author and get a gray circle with the same symbol.
- The colors are computed in the frontend from the key; no color is stored.

## Interaction rules

**Subscribe toggle**

- One icon button with two states, used in the group header and in every discover row: an outlined plus when not subscribed, a filled star when subscribed. It has a tooltip and an accessible label.
- Subscribing takes one click, without a dialog. The icon changes immediately and the group appears in the sidebar with a running-synchronization icon.
- Unsubscribing asks for confirmation, because it deletes the group's cached articles and read state.

**Dialogs**

- Confirmation dialogs are used only for actions that delete data: unsubscribing, removing the server.

**Feedback**

- Background work (synchronization, group list refresh) shows a running indicator where its result appears; it never blocks the UI.
- Errors from background work are shown as status icons with an explanation on hover or click, not as pop-ups.

## Not in the MVP

- **Other languages.** The UI is in English only.
- **Dark theme.** The MVP has a light theme only. shadcn/ui supports dark mode, so it can be added later.
- **Mobile layout** ([deferred](../open-questions.md#deferred-beyond-the-mvp)). The sidebar can be collapsed, but there is no dedicated layout for narrow screens.
