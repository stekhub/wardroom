# 017. Installable web app

- Status: Proposed
- Date: 2026-11-23
- Related: [UI concept](../../product/ui-concept.md), [spec 013](../013-dark-theme/spec.md), [spec 015](../015-accessibility-and-mobile-fixes/spec.md)

## Scope

Wardroom can be added to the home screen of a phone or installed on a computer, and then opens like an app, with its own name and icon. Step 25 of the [implementation plan](../../implementation-plan.md), after 1.0.

## Decisions

Decided by the owner on 2026-11-23:

1. A web app manifest, icons and the tags in the page header.
2. No service worker and no offline use.

## Details added by the AI assistant

Open to a veto by the owner:

1. The app opens without the address bar of the browser (`display: standalone`), at the start page of Wardroom.
2. The bar of the phone takes the color of the theme: one color for light and one for dark.
3. On phones with a notch or rounded corners, the content keeps clear of them.
4. The user guide explains that some browsers install an app only over HTTPS, and that on an iPhone the app keeps its own login, apart from the browser.

## User stories

- As the owner, I open Wardroom from the home screen of my phone like any other app.
- As the owner, Wardroom on my phone has no address bar and its own icon.

## Functional requirements

- The manifest gives the name "Wardroom", the start address `/`, `display: standalone`, and the icons.
- Icons of 192 and 512 pixels, and one of 512 pixels with the logo inside the safe zone for icons that the system cuts into a circle or other shape (`maskable`). An icon of 180 pixels for iPhones.
- The page header has the link to the manifest, the iPhone icon, the name for iPhones and the theme color for light and dark.
- Links that lead out of Wardroom open in the browser, as today.
- The manifest and the icons are reachable without a session, because the system reads them before the login.

## API contracts

None. The manifest is a static file (`/manifest.webmanifest`, media type `application/manifest+json`).

## Technical design

- The manifest and the icons are static files of the frontend, in `frontend/public`. The icons are made from `assets/logo/` once and committed.
- The security configuration lets the manifest and the icons through without a session.
- `viewport-fit=cover` and padding with `env(safe-area-inset-*)` keep the content clear of the notch.
- The Content Security Policy stays unchanged: the manifest comes from Wardroom itself.

## Data model changes

None.

## Error handling

None.

## Security considerations

- The manifest and the icons contain no data of the owner.

## Testing requirements

- The manifest is served without a session, with its media type, and lists the icons, which exist.
- The page header contains the manifest link, the iPhone icon and the theme colors.
- By hand: added to the home screen of an Android phone and an iPhone, it opens without the address bar and with the icon.

## Implementation tasks

1. Icons from the logo.
2. Manifest and the tags in the page header.
3. Security configuration for the files.
4. Safe areas in the layout.
5. User guide: adding Wardroom to the home screen.
