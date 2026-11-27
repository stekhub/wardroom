# Implementation Plan

The order in which the MVP is built, and the status of each step. Each step is one branch and one pull request, small enough to finish and review in one sitting. The specifications define *what* is built; this plan only defines the order and the scope of each step.

## Status

| # | Step | Specs and ADRs | Status |
|---|---|---|---|
| 1 | Project skeleton | ADR 0002, 0003, 0004, 0007, 0008, 0009, 0024, 0027, 0028, 0029, 0030 | Done |
| 2 | NNTP foundation | ADR 0005, 0006, 0016, 0026; open question 1 | Done |
| 3 | Authentication and application shell | Spec 001; UI concept | Done |
| 4 | Server configuration and first-run wizard | Spec 002; ADR 0025; UI concept | Done |
| 5 | Newsgroup discovery | Spec 003; group icons | Done |
| 6 | Subscriptions | Spec 004 | Done |
| 7 | Synchronization core | Spec 005 | Done |
| 8 | Load older, load more, jump to date | Spec 005; ADR 0021, 0022 | Done |
| 9 | Threading | Spec 006 | Done |
| 10 | Article reading | Spec 007 | Done |
| 11 | Discussion view | Spec 008 | Done |
| 12 | Ignoring | Spec 010 | Done |
| 13 | Posting | Spec 009 | Done |
| 14 | Cache size limit | ADR 0023, 0034; spec 005 | Done |
| 15 | Activity of a group | Spec 003 | Done |
| 16 | Polish and documentation | UI concept; spec 001, 002; ADR 0032 | Done |
| 17 | Release pipeline | ADR 0009, 0024 | Done |
| 18 | Security improvements | Spec 011, 012; ADR 0035 | Done |
| 19 | Dark theme | Spec 013; UI concept | Done |
| 20 | Next unread, mark as unread and keyboard shortcuts | Spec 014 | Done |
| 21 | Accessibility and mobile fixes | Spec 015, 022, 023; UI concept | In review |
| 22 | Full-text search | Spec 016 | Not started |
| 23 | Classic view | Spec 019; spec 008 | Not started |
| 24 | Choosing the groups of a post | Spec 024; spec 009 | Not started |
| 25 | Release 1.0 | Spec 025; ADR 0004; CONTRIBUTING | Not started |
| 26 | Windows app (after 1.0) | Spec 026; ADR 0036, 0037 | Not started |
| 27 | Installable web app (after 1.0) | Spec 017 | Not started |
| 28 | Notifications (after 1.0) | Spec 018 | Not started |
| 29 | Bookmarks (after 1.0) | Spec 020; ADR to be written | Not started |
| 30 | Newest articles (after 1.0) | Spec 021 | Not started |

Status values: `Not started`, `In progress`, `In review`, `Done`.

Wardroom can first be used for reading after step 11.

## Steps

### 1. Project skeleton

- Maven project with the Maven Wrapper, Maven Enforcer (Java 25), Spring Boot 4, Spring Modulith with the module verification test.
- Maven group `io.github.stekhub`, base package `io.github.stekhub.wardroom`, version `0.9.0-SNAPSHOT` (0.1.0-SNAPSHOT until step 16). Modules: auth, server, nntp, groups, sync, articles, posting, ignoring, settings.
- The data directory is a property that defaults to `/config`; development uses `./config`.
- SQLite, Flyway and jOOQ code generation from the migrated schema; WAL mode and `busy_timeout` (ADR 0003).
- Startup sequence of ADR 0004: Flyway first, backup with `VACUUM INTO` before pending migrations, downgrade protection, the 2 newest backups kept (ADR 0028).
- Health endpoint, springdoc-openapi, Problem Details; `openapi.json` committed with its test (ADR 0027).
- Logging setup: format, `WARDROOM_LOG_LEVEL`, escaping of untrusted text, the `PROTOCOL_ANOMALY` line (ADR 0029). Coverage report (ADR 0030).
- Frontend: Vite, React 19, TypeScript strict, React Router, TanStack Query, generated API client, Tailwind v4 and shadcn/ui with the design tokens, Inter bundled, Lucide, ESLint, Prettier, Vitest and MSW; built by Maven into the single jar.
- `.gitattributes` that keeps NNTP fixtures byte-for-byte (CRLF).
- Dockerfile (multi-stage, amd64 and arm64) with the `PUID`/`PGID` entrypoint, `HEALTHCHECK` and `compose.yaml` as in ADR 0009. Built locally only; publishing is step 17.
- CI workflow: build and tests on every push and pull request.
- Fill in the "Commands" section of `CLAUDE.md`.

**Done when:** `mvnw verify` builds the jar with the frontend and passes; the container starts with a `/config` volume and is healthy; an empty page is served on port 8119.

### 2. NNTP foundation

- Library spike: verify Apache Commons Net for `CAPABILITIES`, streaming `LIST ACTIVE`, `LIST NEWSGROUPS`, `LIST OVERVIEW.FMT`, `OVER` and `POST` (open question 1). Record the result; if something is missing, propose how to handle it before continuing. The spike may include a manual, read-only check against a public server that needs no login; automated tests never use a real server.
- Mock NNTP server in the test sources, scriptable, with fault injection (ADR 0008).
- `NntpClient` adapter: connect with implicit TLS or none (ADR 0016), authentication, `CAPABILITIES`, timeouts; protocol anomalies logged as in ADR 0029, with `AUTHINFO PASS` masked.
- Per-server connection pool with the connection reserved for interactive actions (spec 005), Resilience4j circuit breaker and Spring Framework retry with the values of ADR 0026 (ADR 0006).

**Done when:** the adapter connects, authenticates and reads capabilities against the mock server, including the failure cases.

### 3. Authentication and application shell

- Spec 001 completely, except the installation documentation (step 16).
- Application shell from the UI concept: sidebar frame, routing, settings page frame with section navigation, toasts, skeletons.
- First-run wizard step 1 (account).
- Logo: the files in `assets/logo/`, a light and a dark variant. The MVP uses the light variant. The favicon is derived from it as a square image.

### 4. Server configuration and first-run wizard

- Spec 002 completely, including `WARDROOM_ENCRYPTION_KEY_FILE` (ADR 0025), except the documentation (step 16).
- Wizard step 2 (server), resuming after a closed browser; settings section "Server".
- The group list fetch trigger is wired in step 5.

### 5. Newsgroup discovery

- Spec 003 completely, including the group icon function and its tests, and the scheduled group list refresh (ADR 0020). Icon names that current Lucide has renamed are mapped, and the table in Group Icons is updated.

### 6. Subscriptions

- Spec 004 completely: subscribe toggle, sidebar entries, unsubscribe cleanup, mark all as read. Unread counts stay 0 until articles exist (step 7).

### 7. Synchronization core

- Spec 005: schedule, first and incremental synchronization in chunks, synchronized ranges, expiry, renumbering, manual refresh, settings section "Synchronization" (without the cache size, step 14).

### 8. Load older, load more, jump to date

- Spec 005 and ADR 0021, 0022: extending ranges, date search, articles arriving read.

### 9. Threading

- Spec 006 completely, including the recomputation hooks of steps 7 and 8.

### 10. Article reading

- Spec 007 completely: content fetch, Mime4j extraction, read marking, Content Security Policy, author icons, excerpts, leaving page.
- Before the links are built: propose to the owner a rule for where a URL in the text ends (a full stop or a closing bracket after it, angle brackets, an address broken over two lines). Spec 007 has none.

### 11. Discussion view

- Spec 008 completely: thread list, sorting, paging, date view, thread page, mark thread as read.
- "Load older discussions" in the normal view, with the `rangeId` of the page of thread cards. The backend exists since step 8.

### 12. Ignoring

- Spec 010 completely: rules, author key, filters in lists and counts, settings section "Ignored".

### 13. Posting

- Spec 009 completely, including posting availability in the connection test.

### 14. Cache size limit

- ADR 0023 and the cache parts of spec 005: measurement, cleanup job, setting with current use, warning.
- Switching the limit off (added by the owner on 2026-11-22): the owner can switch the cache size limit off in the synchronization section of the settings page. Without a limit, the cleanup does not run and nothing is deleted; the section still shows the current use. ADR 0034 describes it; the owner accepted it on 2026-11-22.

### 15. Activity of a group

Added to the MVP by the owner on 2026-11-22. The change of spec 003 and of the data model was written with the step; the owner accepted it on 2026-11-22. The direction:

- The group list shows how many articles a group gets per day, and can be sorted by it.
- The group list refresh already reads the high watermark of every group, so no further commands to the server are needed.
- Per group, two baselines are stored, an older and a younger one, each with a time and the high watermark at that time. No history is kept.
- At a refresh, the activity is the high watermark now minus that of the older baseline, divided by the time since the older baseline.
- When the younger baseline is 7 days old, it becomes the older one, and the values of this refresh become the younger one. The activity is therefore a plain count over the last 7 to 14 days.
- The activity does not depend on how often the list is refreshed. After a time without refreshes it is the average over that longer time.
- The first baseline is stored with the first load of the group list.
- A value is shown once the older baseline is at least 24 hours old, because a shorter time follows the time of day. Until then the list says that activity is shown after one day. In the first week the value is rough.
- If the high watermark is below a baseline, the group was renumbered: both baselines start over and the group has no value.
- Groups without a value are sorted last.
- The high watermark of the group itself cannot serve, because the synchronization of a subscribed group moves it.
- The value is approximate, because cancelled articles and spam count too.
- A moving average was considered and dropped: it depends on the whole history of refreshes, a burst fades out slowly instead of dropping out, and its value cannot be checked.
- Shown as a number, not as levels; decided during the step and accepted by the owner.

### 16. Polish and documentation

- Settings section "About" (agreed with the owner on 2026-11-23): name, logo and version ("Development build" without version information); the license AGPL-3.0-or-later with links to `LICENSE` and `LICENSE-ADDITIONAL-TERMS.md`; links to the source code, the user guide and the issues of the repository. The version comes from a new endpoint `GET /api/about`. No update check. Check that the image ships the license of the bundled Inter font.
- Version: the project version becomes 0.9.0 (decided by the owner on 2026-11-23); in development `0.9.0-SNAPSHOT`.
- The quick switcher, the accessibility pass and the shorter sessions without "Remember me" were moved beyond the MVP by the owner on 2026-11-23 ([open questions](open-questions.md)).
- Done: application version in backup names (ADR 0032): store the version at every start, name the backup after the stored version, log both versions, and adapt the cleanup of old backups to the new file name. Add the section on going back to an earlier version to the user guide.
- README: introduction, quick start with the reference Compose file, first-run wizard, link list to the user guide (ADR 0031).
- User guide (`docs/user-guide.md`): backups, updates, password reset (spec 001), lost key file (spec 002), and an advanced section on keeping the encryption key outside `/config` (ADR 0025, 0031).
- The README and the user guide were written early, on 2026-11-19. In this step: check both against the finished application and replace the screenshot placeholder in the README with a real screenshot.

### 17. Release pipeline

The owner made the repository public and asked for the release of 0.9.0 on 2026-11-23.

- Workflow `.github/workflows/release.yml`, started by pushing a tag `vX.Y.Z`. It stops if the tag differs from the version in `pom.xml`, runs `mvnw verify`, builds the image for amd64 and arm64, starts both and waits until they are healthy (ADR 0024), publishes them to `ghcr.io/stekhub/wardroom` and creates the GitHub release with generated notes.
- The jar is built once: the build stage of the Dockerfile runs on the runner's architecture, and arm64 is emulated only for the runtime stage and the health check.
- The version in `pom.xml` is `0.9.0` for the release. After the release, the next development version is set on a new branch.
- The steps of a release are in `CONTRIBUTING.md`; the tags of the image are explained in the user guide.
- The README says that the image runs on arm64. The line saying that no image is published yet was not needed: the release follows this step.
- Open to a veto (own decisions): the tags `0.9.0`, `0.9` and `latest`, without a tag for the major version while it is 0; no provenance attestation and no SBOM, because GitHub lists the attestation as a platform "unknown/unknown"; the OCI labels title, description, license `AGPL-3.0-or-later` and source.
- For the owner, in the repository settings: turn on private vulnerability reporting (Settings, Security), because `CONTRIBUTING.md` points to it. It is still off.
- After the first release: check that the package `wardroom` on GitHub is public, so it can be pulled without a login.

### 18. Security improvements

The first step after the release of 0.9.0, decided by the owner on 2026-11-23. The specs were refined with the owner and accepted on 2026-11-23.

- Login throttling tied to the account ([spec 011](specs/011-login-throttling-and-session-timeout/spec.md)).
- Failed logins are logged with the client address ([spec 011](specs/011-login-throttling-and-session-timeout/spec.md)).
- Sessions without "Remember me" end 30 minutes after the browser stopped sending requests, so a closed browser is logged out; an open page keeps its session ([spec 011](specs/011-login-throttling-and-session-timeout/spec.md)).
- Two-factor authentication with an authenticator app, turned on in the settings, with a QR code that has the Wardroom logo in the middle ([spec 012](specs/012-two-factor-authentication/spec.md)).
- Not part of this step: the easier password reset trigger (ADR 0017) and the NNTP password from a file, which stay [deferred](open-questions.md#deferred-beyond-the-mvp).

### 19 to 25. On the way to 1.0

Decided by the owner on 2026-11-23. Version 1.0 is kept to the core done well: reading, writing and finding, secure and polished. Larger optional features follow after 1.0 (steps 26 to 30). The order matters: each step builds on the ones before. The specs were written from the decisions below on 2026-11-23; the owner accepts each one, or changes it, before its step.

Changed by the owner on 2026-11-24, after a comparison with what long-time readers of newsgroups expect: the classic view moved before 1.0 (step 23), more keyboard shortcuts were added to step 20, ignored subject words to step 21, a notice on what the search covers to step 22, and choosing the groups of a post became step 24. Several servers and importing subscriptions from another reader stay [deferred](open-questions.md#deferred-beyond-the-mvp).

### 19. Dark theme

[Spec 013](specs/013-dark-theme/spec.md), accepted on 2026-11-24.

- A dark theme next to the light one, with the choice Light, Dark or System in the settings. The default is System.
- The choice is stored in the browser, so each device has its own.
- The logo in the application and the icon in the browser tab follow the theme.
- First, so that the later steps are built for both themes from the start.

### 20. Next unread, mark as unread and keyboard shortcuts

[Spec 014](specs/014-next-unread-and-mark-as-unread/spec.md), accepted on 2026-11-24.

- The key `N` opens the next unread article, across threads in the current sort order. At the end of a group, it goes to the next group with unread articles.
- More keys, added by the owner on 2026-11-24: `Space` scrolls the article and, at its end, does what `N` does; `J` and `K` go to the next and the previous article; `R` replies to the current article; `C` marks the current thread as read; `?` shows the list of keys. The letters were proposed by the AI assistant, open to a veto.
- Opening an article with `N` marks it as read at once, as reading does today.
- Mark as unread: the owner marks an article as unread again.
- The key does nothing while the owner types in a field.
- A setting "Keyboard shortcuts", on by default, turns all these keys off, because WCAG 2.2 criterion 2.1.4 asks that a shortcut of a single letter can be turned off. Proposed by the AI assistant and accepted by the owner. It is stored in the browser, like the theme.

### 21. Accessibility and mobile fixes

[Spec 015](specs/015-accessibility-and-mobile-fixes/spec.md), accepted on 2026-11-24.

- A systematic check of the whole application against WCAG 2.2 AA, with the keyboard alone, with the screen reader NVDA, and zoomed to 200 and 400 percent, in both themes. The spec has a short checklist for the screen reader test. No automated accessibility checks.
- The mobile layout is fixed where needed, also because WCAG 2.2 asks that pages work at 320 pixels wide: the menu closes after a group or another page was chosen, buttons that take too much space show only their icon below 768 pixels, with a tooltip and a label, and every page is checked at the width of a phone.
- Group list filters and paging ([spec 022](specs/022-group-list-filters-and-paging/spec.md), accepted on 2026-11-24 and changed the same day): binary groups are hidden in the group list while the setting "Hide binary groups" in the section "Ignored" is on, which it is by default. They are found by naming rules and a built-in list, which the owner checks and completes against a real server during the step. The owner can also ignore a group; it no longer appears in the group list and is listed in the section "Ignored". A paging row at the bottom jumps to any page.
- Ignored subject words ([spec 023](specs/023-ignored-subject-words/spec.md), accepted on 2026-11-24), added by the owner on 2026-11-24. The points below were proposed by the AI assistant:
  - In the settings section "Ignored", the owner adds a word or a phrase, for all groups or for one subscribed group.
  - A thread whose card article has the word in its subject is not shown in the thread list, and its articles do not count as unread, as for an ignored thread (spec 010). Upper and lower case do not matter, and whole words match, not parts of words.
  - A filter, not a change: removing the word shows everything as before.
  - The list shows each word with its group or "All groups", the date it was added and a remove button.
- After the frontend changes before it, so that it checks them. Later frontend steps are checked with the same checklist (`docs/accessibility-checklist.md`).

### 22. Full-text search

[Spec 016](specs/016-full-text-search/spec.md), accepted on 2026-11-24.

- One search over all cached articles, by words in the subject, the author and the text, with the results on a new page. Not limited to one group.
- Only what is cached is searched; no content is fetched in advance for it. The text of an article is found once it was opened, the subject and author of every synchronized article.
- All words must match; a phrase can be put in quotes. No other operators.
- Results are ordered by best match.
- The search field is in the top bar of every page.
- The results page is a simple list of articles. Each entry shows the subject, the author, the group and the date, and opens its thread at that article. More results are loaded like the thread list.
- Added by the AI assistant, open to a veto: each entry also shows a short excerpt around the words found, with these words in bold, so the owner sees why it matched. The excerpt is untrusted text and is drawn as text, never as HTML.
- A notice on what the search covers, added by the owner on 2026-11-24: a line above the results says "Searches the text of the articles you have opened, and the subject and author of all articles." So nobody takes a missing result for a broken search. Asked for in the usability step 21, but placed here, because the search page is built in this step.

### 23. Classic view

[Spec 019](specs/019-classic-view/spec.md), accepted on 2026-11-24. Moved before 1.0 by the owner on 2026-11-24, because long-time readers of newsgroups skim a group in a compact list of subjects.

- A second way to show the groups, next to the card view ([spec 008](specs/008-discussion-view/spec.md)): the subscribed groups on the left as today, a compact tree of the threads at the top, and the selected article below it.
- The choice applies to all groups: the whole interface switches to the classic view.
- The tree has a toggle to open or close a thread, the subject indented by reply depth, the author and the date. Unread articles are bold, with a dot. It is in thread order, newest thread first, as the cards; no sorting or resizing of columns.
- Only on wide screens. On a narrow screen (below 768 pixels) the card view is shown, whatever the choice; the setting says so.
- The keys of step 20 and mark as unread work in it too, and it is checked for accessibility as in step 21.

### 24. Choosing the groups of a post

[Spec 024](specs/024-groups-of-a-post/spec.md), accepted on 2026-11-24. Added by the owner on 2026-11-24. It changes "Target groups" in [spec 009](specs/009-article-posting/spec.md), where a new discussion goes to the current group only and the groups of a reply cannot be changed. When a post is crossposted to many groups, every reply goes back to all of them, against the custom of Usenet. The points below were proposed by the AI assistant:

- The groups of a new discussion and of a reply can be edited in the editor. A new discussion starts with the current group, a reply with the groups of its parent (`Followup-To`, otherwise `Newsgroups`), as today.
- Groups are added from the group list of the server, with a search as on the discover page; unknown names are not accepted. Groups that do not allow posting are not offered.
- With more than one group, a field "Follow-ups to" sets the header `Followup-To`, so replies go to one group. A reply to a crosspost suggests the group the owner reads it in.
- A warning appears from 4 groups on, because many servers and readers treat wide crossposts as spam; sending is still possible.
- The post is stored with the group it was written in, as today (`posted_from_newsgroup_id`).

### 25. Release 1.0

- A check of the user guide against the finished application, and a test of the upgrade from a database of version 0.9.0.
- A hint that a newer version is available ([spec 025](specs/025-update-hint/spec.md), accepted on 2026-11-24). Asked for by the owner on 2026-11-24. From 1.0 on, people have versions to update from. The points below were proposed by the AI assistant:
  - The backend asks the releases of the project on GitHub once a day for the latest release and compares its version with its own. Pre-releases are ignored, and a development build never reports an update. The browser is not involved, so the Content Security Policy stays as it is.
  - The hint is quiet: a mark on "Settings" in the sidebar and a line in the settings, "Wardroom 1.1.0 is available", with a link to the release notes. No dialog and no toast. It is shown only after login.
  - Wardroom never updates itself; it only tells the owner. The user guide explains the update.
  - A setting turns the check off; it is on by default. The user guide says what the check sends: one request with the address of the server and the version of Wardroom, nothing else.
  - Open to a veto: "Dismiss" hides the hint until the next version; a failed check is logged once and tried again the next day, without a message in the browser; once notifications exist (step 28), a new version can also be sent there.
- Then version 1.0.0 is released as described in `CONTRIBUTING.md`.

### 26 to 30. After 1.0

Decided by the owner on 2026-11-23, and moved behind 1.0 to keep it small. The order is a starting point and is adjusted to what people ask for after 1.0. Steps 26 to 30 have proposed specs; they are checked again before each step, because the steps before may change them.

### 26. Windows app

Decided by the owner on 2026-11-23: the first step after 1.0, because the owner wants it. [Spec 026](specs/026-windows-app/spec.md), [ADR 0036](architecture/decisions/0036-windows-app.md) and [ADR 0037](architecture/decisions/0037-key-protection-with-dpapi.md), accepted on 2026-11-26.

Decided by the owner on 2026-11-26:

- One switch, `wardroom.windows-app=true`, set only by the launcher of the installer, turns the desktop mode on. Wardroom does not guess it from the operating system.
- The web server listens on `127.0.0.1` only and refuses requests with another `Host` header, against DNS rebinding. The login stays, because other users of the same computer can reach the port.
- The key is protected with DPAPI in the desktop mode and stays a key file everywhere else, also for the image on Windows. A copy of the data folder then does not reveal the NNTP password or the secret of the second factor; moving the folder to another computer or user loses the key.
- Signing comes later and only for the direct download; winget does not need it. The first choice is the SignPath Foundation, which signs open-source projects for free.

**The application**

- Wardroom runs on Windows without Docker, in its own window. It is the same application as the image, with a small desktop mode on top: it listens on `127.0.0.1` only, on a free port, keeps its data in the user's own application data folder, and a second start brings the open window to the front.
- The window is Edge in app mode, which is part of Windows 10 and 11, without an address bar or tabs. If Edge cannot be found, the default browser opens.
- A short start page is shown while the application starts.
- An icon in the notification area offers "Open", "Start with Windows" and "Quit". "Start with Windows" is off by default; it lets Wardroom synchronize and notify while the computer is on. Added by the AI assistant and accepted by the owner.
- The Java runtime is included, so no Java has to be installed.

**The installer**

- An MSI built with `jpackage` on a Windows machine of the release workflow.
- A fixed upgrade ID (`--win-upgrade-uuid`), so a new version replaces the old one.
- Installed per user (`--win-per-user-install`), without an administrator prompt.
- A Start menu entry and a desktop shortcut by default. winget installs silently, so the defaults apply there; an installer started by hand asks.
- The MSI is attached to each GitHub release, as a regular installer for a direct download. It is not signed at first; the user guide explains the SmartScreen warning when it is downloaded directly.

**Distribution with winget**

- winget is the way to install on Windows: `winget install stekhub.Wardroom`. The README and the user guide show the command. Updates with `winget upgrade stekhub.Wardroom`.
- Once, by hand: `wingetcreate new` with the address of the first MSI creates the package `stekhub.Wardroom` (publisher, license AGPL-3.0, description, project address) and opens a pull request to Microsoft's package repository. Microsoft checks the package automatically and a moderator merges it; the first time takes a few days.
- Once, by the owner: a GitHub access token that lets `wingetcreate` open pull requests, stored as a secret of the repository.
- For each release, the workflow builds the image as today, builds the MSI and attaches it to the release, then runs `wingetcreate update stekhub.Wardroom` with the new version and address. The new version is usually available within a day.
- If the winget step fails, for example because the token expired or a check failed, the rest of the release is not affected. The pull request explains the reason.
- `CONTRIBUTING.md` describes the token and what to do when the winget step fails.

### 27. Installable web app

[Spec 017](specs/017-installable-web-app/spec.md), proposed on 2026-11-23 and waiting for the owner.

- Wardroom can be added to the home screen of a phone and opens there like an app, with its own name and icon: a web app manifest, icons and the tags in the page header. No service worker and no offline use.
- Moved after 1.0 by the owner on 2026-11-23. It is small and depends on no other step, so its place can change.

### 28. Notifications

[Spec 018](specs/018-notifications/spec.md), proposed on 2026-11-23 and waiting for the owner.

- A bell on threads and on groups turns notifications on. A thread the owner posts in gets the bell on its own, so replies to own articles are notified.
- A thread with the bell: a notification when a synchronization finds a new reply in it, so within the synchronization interval (15 minutes by default).
- A group with the bell: once a day, one summary with the number of new articles per group with the bell. At 20:00 in the time zone of the container by default; the time is a setting.
- Notifications are sent to one or more notification URLs set in the settings, each with a test button. The URLs follow the common format of the Shoutrrr library, so a URL written for another self-hosted application works here too. Wardroom implements the format itself and starts with `ntfy://` and `generic://` (a plain HTTP request to any webhook); further services on demand. No browser push.
- A notification URL can contain a token, so it is stored encrypted like the NNTP password and never logged.
- The user guide names ntfy as an example service. The owner asked for this exception to the rule that the docs name no other applications.

### 29. Bookmarks

[Spec 020](specs/020-bookmarks/spec.md), proposed on 2026-11-23 and waiting for the owner.

- The owner bookmarks an article. A thread is bookmarked through its first article, so there are no separate thread bookmarks. This also replaces the deferred item "keeping articles".
- "Bookmarks" in the sidebar opens a page with the bookmarked articles, newest bookmark first. Each shows the group, subject, author, date and an excerpt, and opens its thread at that article.
- A bookmarked article is kept when the cache is cleaned.
- Unsubscribing removes the bookmarks of the group with its articles; the confirmation says so.

### 30. Newest articles

[Spec 021](specs/021-newest-articles/spec.md), proposed on 2026-11-23 and waiting for the owner.

- One page with the newest articles of all subscribed groups, as single articles that open their thread. Read and unread articles are shown, unread ones marked; ignored ones are left out.
- The page is the start page after login.

## Working on a step

1. Read this plan, `CLAUDE.md`, the step's specs and the ADRs and documents they reference.
2. Create a branch `feature/step-NN-short-title` from `main`. Set the step to `In progress`.
3. Implement the step's scope and its specs' testing requirements. Keep to the step; note anything that belongs elsewhere under "Notes" below.
4. Run the full build and tests (`mvnw verify` must pass, ADR 0030); start the application and check the step's feature in the browser.
5. Set the step to `In review` and summarize for the owner: what was built, how to try it, deviations and open points. The owner reviews; commits and pull requests are made when the owner asks.
6. After merging, set the step to `Done`.

**Stopping mid-step** (for example when a usage limit is near): record under "Notes" what is finished, what is not, and the next action, so a new session can continue from the repository alone.

## Notes

Progress notes, deviations and findings, newest first.

**Step 21, accessibility and mobile fixes, group list filters, ignored subject words (2026-11-25)**

Built:

- Spec 022: the group list leaves out binary groups (`BinaryGroups`, by name) while the setting "Hide binary groups" is on, and ignored groups always. "Ignore group" in each row, with a toast and "Undo". A paging row with "First", "Previous", a field for the page number, "Next" and "Last". The setting and the list "Groups" in the settings section "Ignored".
- Spec 023: ignored words in subjects, for all groups or for one. Migration V16 rebuilds `ignore_rule` (new kinds, `newsgroup_id`, new unique key) and adds `article.subject_words`. The words are computed when an article is stored or a stub is filled in, and at the start for existing articles (`SubjectWordsStartup`). The thread list, next unread and the unread counts leave the hidden threads out. The thread page shows the notice with the word.
- Spec 015: "Skip to content" on every page; focus on the main heading after a change of page; a landmark for the sidebar; the level of each article on the thread page; the start and end of a synchronization as a status message; the mobile menu closes after a page was chosen; buttons with icon and label show only their icon below 768 pixels (`LabelledIconButton`).
- `docs/accessibility-checklist.md` with the test guide for NVDA. The UI concept has a new part "Narrow screens and accessibility"; "Mobile layout" is no longer under "Not in the MVP". Specs 003 and 010, the data model and the user guide are updated.

Found and fixed by the check in the browser, at 375 and 320 pixels wide and in both themes:

- The outline of fields, checkboxes and radio buttons had a contrast of about 1.3:1 in the light and 1.6:1 in the dark theme. A new token `--field-border` gives 3.6:1 and more.
- The red of destructive buttons had less than 4.5:1 on their own tinted background in the light theme. `--destructive` is darker now.
- The star of a subscribed group was a fixed amber with 2.1:1 on white. It uses the token `--warning` now.
- In the group header the name of the group shrank to nothing at 320 pixels. The name keeps its room and wraps; the buttons move below it.
- In the group list a long name was cut off after a few letters. On a narrow screen the name wraps and the activity stands below it.
- Three links were lower than 24 pixels.
- No page scrolls sideways at 320 pixels.

Decisions of the AI assistant, open to a veto:

1. **`SubjectWords` is in the `ignoring` module, not in `articles`.** Spec 023 names the `articles` module. The rule needs the same normalization, and `articles` already depends on `ignoring`, so the other place would be a cycle between the modules.
2. **No `ServerRemovalCleanup` in the `ignoring` module.** The foreign key of `ignore_rule.newsgroup_id` deletes a rule with its group (`ON DELETE CASCADE`), which does not depend on the order of the cleanups. A test covers it.
3. **A word needs no escaping for `LIKE`.** The normalized word has only letters, digits and single spaces.
4. **"Hide binary groups" is a checkbox, not a switch.** The settings use checkboxes for such choices; the component library of the project has no switch installed.
5. **The level of a reply is a text for screen readers** ("Level 2"), not `aria-level`, which screen readers do not read on list items.
6. **The focus after a change of page** goes to the heading only when the owner has not moved into the page meanwhile. Within the settings it goes to the heading of the section. The first page a browser loads keeps the focus where the browser puts it.
7. **The paging row uses the same icon-only buttons** below 768 pixels, so it fits a phone.
8. **The buttons of the article editor stay as text.** "Send" and "Cancel" have no icon and fit at 320 pixels. Spec 015 lists the editor among the places for icon-only buttons.
9. **Saving one settings section sends the values of the others as they were loaded.** The endpoint takes all settings at once.
10. **An address with a page beyond the last one** shows a sentence and the paging row, which leads back.

Open, for the owner:

- **Binary groups:** the owner checked the group list of a real server on 2026-11-25; the naming rules and the built-in list in `BinaryGroups` and spec 022 were extended from it. The owner tests the list again. Eight more groups from the check are ignored by default, with rules that the migrations V17 and V18 create (decided by the owner on 2026-11-25).
- **The check with NVDA is not done.** The AI assistant cannot run a screen reader. The checklist has the guide; its result goes into the pull request (spec 015).
- **Zoom to 200 and 400 percent and larger text spacing** were checked through the width of 320 pixels, which is what 400 percent of a window of 1280 pixels gives. A check with the zoom of a real browser is still worth doing.
- The search (spec 016) and the classic view (spec 019) do not exist yet. They have to leave out the threads that a word hides when they are built; `IgnoreFilters.subjectIgnored` is there for it.
- When the menu of a narrow screen opens, the tooltip "Collapse or expand the sidebar" shows at once, because its button gets the focus. Not fixed.
- Quote bars and the circles of group icons still use fixed colors outside the tokens. They are decoration; the checklist lists them under "Known limits".

`mvnw verify` passes with 737 backend tests and 322 frontend tests.

**Hardening after a review: login throttling per account, limits for NNTP answers (2026-11-24)**

Built:

- Login throttling per account (specs 001 and 011, changed with the owner's decision of 2026-11-24). Before, every failed login counted, whatever username was entered, so anybody could hold up the login of the owner. Now only logins with the username of the account count and wait.
- Limits for what an NNTP server sends (spec 002): a line of at most 1 MB and an answer to `CAPABILITIES` of at most 1,000 lines. New protocol anomaly kind `response.too-long`; the connection is closed.
- `LineLimitInputStream` sits below the readers of the NNTP library, so the limit also holds for the lines the library reads itself, the greeting included.

Decisions of the AI assistant, open to a veto:

1. **No counter before an account exists.** The counter in memory for that time is gone; there is no username to count for.
2. **Logins with an unknown username are checked one after the other, apart from the logins to the account.** They are not throttled any more, so this keeps them to one password hash at a time and out of the way of the owner's login.
3. **An answer that is too long is a `PROTOCOL` failure**, like a malformed status line. Such a failure is not tried again and does not count for the circuit breaker.
4. **The anomaly line has no part of what the server sent.** The line is too long by definition and may be the text of an article. It names the limit instead.

Findings:

- Logins with an unknown username are logged one by one and nothing but the time of a password hash limits them. Before, the throttling limited them too. A flood of such lines is possible; it shows the address it comes from.
- A third point of the review, the switched-off limits of the MIME parser, was not changed. The parser only gets an article of at most 10 MB that is already in memory, parts and nesting have limits of their own, and the default limits of the library reject lines that real articles have.

**Step 20, next unread, mark as unread and keyboard shortcuts (2026-11-24)**

Built:

- Spec 014: the endpoints `GET /api/groups/{id}/threads/next-unread` and `DELETE /api/articles/{id}/read`; the keys `N`, `Space`, `J`, `K`, `R`, `C` and `?` in the group view and on the thread page; the address `?article=`; "Mark as unread" in the menu of an article; the setting "Keyboard shortcuts" in the section "Preferences".
- `next-unread` uses the order and the filters of the thread list (`ThreadListRepository`), so ignored threads and authors are skipped and the date view stays within its range.
- Specs 007 and 008, the UI concept and the user guide (new part "Reading with the keyboard") are updated.
- Two corrections to the dark theme of step 19: the toasts follow the theme, and the overlay behind a dialog is darker in the dark theme.

Decisions of the assistant, open to a veto:

1. **When the current thread is not in the list**, for example because it is ignored or outside the date view, `next-unread` begins at the top of the list.
2. **At the end of a group**, `N` first goes to the next group with unread articles. Only when no other group has any does it look again from the top of the current group, which can have unread threads above the current one. When only the current thread has unread articles left, above the current article, it goes to the first of them. Otherwise it says "No unread articles".
3. **Going from thread to thread with `N` replaces the page in the browser history**, so Back still returns to the list.
4. **`N` within a thread skips collapsed articles** and articles located only in other groups. `J` and `K` move through the articles that are shown.
5. **An article that a key reaches is marked as read at once**, not only when its text scrolls into view, and it is highlighted briefly, as with `?article=`.
6. **`Space` scrolls by one screen less 48 pixels**, so the last lines stay in view. On a button or a link, `Space` presses it, as it always does.
7. **The keys also do nothing while a menu is open, and with Shift**, except `?`, which needs Shift on most keyboards. `?` works on every page. A key that is held counts once, so holding it does not mark many articles as read; held `Space` scrolls as the browser does.
8. **In the group view, `J` without a card with the focus goes to the first card; `C` without one does nothing.**
9. **"Mark as unread" is also offered for an article that was read while the page is open.**
10. **The setting is a checkbox**, and the dialog of `?` has a Close button besides Escape.

Verified:

- `./mvnw verify` passes: 688 backend tests and 296 frontend tests.

**Step 19, dark theme (2026-11-24)**

Built:

- Spec 013: dark tokens in `src/index.css`, the theme script `public/theme.js` in the page header, the theme state in `src/theme/themeState.ts`, the settings section "Preferences" with Light, Dark and System, and the dark logo in the sidebar, on the login and setup pages and in "About". The favicon has both variants and shows the one of the operating system.
- The dark header logo is made from `assets/logo/wardroom-header-dark.svg` in the same way as the light one: the mark 50 % larger, the lettering moved next to it.
- The UI concept, `CLAUDE.md` and the user guide (new part "Light and dark theme") are updated.

Decisions of the assistant, open to a veto:

1. **"Preferences" comes after "Ignored" and before "Account"** in the settings navigation. The spec does not say where.
2. **The theme is chosen with three radio buttons** (shadcn/ui `radio-group`, added with this step), not with a select, so all three choices are visible at once.
3. **The accent color of the dark theme is the light blue of the dark logo, `#4db5fb`, with dark text on it.** White text on a blue that is light enough for a dark background does not reach 4.5:1. Buttons in the dark theme therefore have dark lettering.
4. **A new token `warning`** for the three hints in amber (mostly quoted text, long signature, cache limit not reachable). They used `text-amber-700`, which has only about 3.5:1 on the dark background. The token is amber 700 in the light theme and amber 400 in the dark one.
5. **The quote bars keep their colors** (sky, emerald and amber 600). They reach at least 4.3:1 on the dark background and the dark cards, above the 3:1 for graphics.
6. **The QR code stays black on white** in the dark theme, as spec 012 requires, and keeps the light logo in its middle.
7. **The dark surfaces are a soft dark gray, not near black:** the background is `#121212`, cards and the sidebar `#1c1c1c`, controls `#2b2b2b`. The values of shadcn/ui are darker; the owner asked for a softer dark.

Contrast in the dark theme, computed from the tokens: text 16.4:1 on cards, muted text 6.9:1 on cards and 5.7:1 on controls, the accent color 7.6:1 on cards, the warning color 10.2:1, the destructive color 5.9:1, dark text on the accent color 8.8:1.

Verified:

- `./mvnw verify` passes: 682 backend tests and 273 frontend tests.
- Tried by hand with the built jar on an empty data directory, with the browser set to dark: the setup pages, the sidebar and the settings follow System; Light and Dark take effect at once and stay after a reload.
- Not tried: the views with articles, because the check had no NNTP server.

**Step 18, security improvements (2026-11-24)**

Built:

- The project version is `0.10.0-SNAPSHOT`.
- Spec 011: the login throttling keeps its state in the account row (migration 14) and counts every failed login, whatever username was entered. While no account exists it stays in memory. A login sets how long its session lasts without requests: 30 minutes, or 7 days with "Remember me"; the session after setup gets the 30 minutes. Sessions from before the update keep the 7 days. The subscription list also asks while the tab is hidden. Failed logins, the start of a waiting time and successful logins are logged with the client address and, if the request has one, `X-Forwarded-For`, both escaped.
- Spec 012: two-factor authentication (migration 15, [ADR 0035](architecture/decisions/0035-two-factor-authentication.md)). The key loader and the cipher moved to the `platform` module as `SecretCipher`, with a purpose and format version 2; stored NNTP passwords of version 1 stay readable. The login has a second step; the settings section "Account" has "Two-factor authentication" with the QR code, the recovery codes, "Create new recovery codes" and "Turn off".
- The QR code is drawn by the frontend as an SVG from the modules that the package `qrcode-generator` computes (MIT license), with the highest error correction and the logo in a square in the middle that covers about 5 % of the code. It is always black on white.
- Spec 001, the data model, the UI concept and the user guide are updated. The user guide has a new part "Login and security".

Decisions of the assistant, accepted by the owner on 2026-11-24:

1. **A recovery code still logs in when the key file was lost.** Spec 012 says that a login with two-factor authentication is then not possible and points to the password reset. The recovery codes are stored as hashes and do not need the key, so refusing them would need extra code and would only make the way back harder. The log and the user guide name both ways: a recovery code, or the password reset. ADR 0035 records it, and spec 012 was changed to match.
2. **Turning off and creating new recovery codes accept a recovery code as "a code".** The spec says "the current password and a code". With a lost phone, this is how the owner turns two-factor authentication off without a password reset.
3. **The code that turned two-factor authentication on counts as used.** A login right afterwards needs the next code of the app, at most 30 seconds later.
4. **A second "Turn on" while it is on is refused** with `400`, so the secret that is in use cannot be replaced without turning it off first.
5. **Format version 1 is read only for the purpose `nntp-password`.** Nothing else was ever written in it, so a version 1 value cannot be passed off as a secret of another purpose.
6. **The two messages of the second step differ.** "The code is wrong." and "The login took too long. Enter your password again." Both are `401`. Only someone who knew the password gets to this step, so the difference reveals nothing.
7. **Wrong codes in the settings count for the login throttling.** At first they were not throttled. A review of the step showed that somebody with a session and the password could then try out all codes within hours and turn two-factor authentication off. The owner decided on 2026-11-24 to throttle them; spec 012 says so now. A wrong code while turning it on counts too. A waiting time is answered with `429` and shown as a message.
8. **A session in the second step lasts 5 minutes** and is replaced by a new one when the code is accepted.
9. **The waiting-time log line** is "Too many failed logins in a row; the next login has to wait N seconds".
10. **The session limits are tested by moving the stored time of the last request back**, as the test of the 7 days did before. Spring Session reads the system clock, so this is the controlled clock of the testing requirements. The codes and the throttling have a real clock that the tests replace.
11. **The dialog of the recovery codes closes only with "Done"**, because the codes are shown once.

Findings:

- Behind a trusted reverse proxy, the web server already replaces the connection address with the forwarded one and removes the header (ADR 0010). The log then shows the address of the visitor without the `X-Forwarded-For` part. This is what the owner wants to see; the user guide describes both forms.

Verified:

- `./mvnw verify` passes: 682 backend tests and 265 frontend tests.
- Tried by hand with the built jar on an empty data directory: turning two-factor authentication on in the settings with the QR code and a code, the recovery codes, logging out, a login with the password and then a wrong and a right code. The log has "Two-factor authentication was turned on", "Failed login from …" and "Login from …" and never the password.
- Not tried: scanning the QR code with a phone. A test decodes the drawn code, with the logo area painted light and dark.

The README still describes version 0.9.0 and does not name two-factor authentication yet.

**Step 16, polish and documentation (2026-11-23)**

The owner goes over the points of the step one by one with the AI assistant before each is built.

Done:

- The quick switcher, the accessibility pass and the shorter sessions without "Remember me" moved beyond the MVP.
- The project version is `0.9.0-SNAPSHOT`.
- Settings section "About" as agreed: `GET /api/about` in the settings module gives the version from the build information, `null` without it. The frontend shows logo, version, license and the links. Every settings section has content now, so the placeholder "There is nothing to set here yet" is removed, and the type of the section paths makes sure each section has content.
- License notices: the built frontend contains `licenses/third-party-licenses.txt` with the license texts of all packages in it, 63 at present, the Inter font included. A Vite plugin of its own writes it at every build (`frontend/licenseNotices.ts`), without a new dependency. It finds the packages through the modules and files of the bundle; the packages that only reach the stylesheet through `@import` are named in `vite.config.ts`. Decided by the owner on 2026-11-23, after the finding below.
- Application version in backup names (ADR 0032): the migrator reads the stored version with plain SQL before the migrations, names the backup `wardroom-{version}-schema{n}-{timestamp}.db`, logs both versions, and stores the starting version after the migrations, at every start. The retention of ADR 0028 counts the old and the new names. A build without version information stores `development`.
- User guide: checked against the application; it matches. Added the section on going back to an earlier version (ADR 0032).

Decisions of the assistant, open to a veto:

1. **The stored version is cleaned before it becomes part of a file name.** Characters other than letters, digits, `.`, `+`, `_` and `-` become `_`, so a changed value cannot lead out of the backup directory.
2. **A version that cannot be stored does not stop the start.** The database is migrated already; only the name of the next backup would say `unknown` or an older version. It is logged as a warning.
3. **The section on going back says to delete `wardroom.db-wal` and `wardroom.db-shm`.** SQLite would otherwise try to apply the journal of the newer version to the restored file. After a clean stop the files are usually gone.
4. **Packages without a license file are listed with the license of their `package.json`** and the sentence "The package contains no license file." One package (`react-remove-scroll-bar`) is such a case.

Findings:

- The built JavaScript had no license notices of the libraries in it, and the font license was not shipped either. The bundler drops these comments. Fixed by the notices file above.
- The user guide has no part about the settings yet; the cache size limit and its switch belong there (note of step 14). Not done in this step so far.

Verified:

- `./mvnw verify` passes: 628 backend tests and 248 frontend tests. Afterwards the plugin is imported with its file extension, because Vite warned about the import without it; type check, lint, format check, the frontend build and all frontend tests were run again.
- Tried by hand with the built jar on a copy of a test instance, set back to schema 12: without a stored version the backup is `wardroom-unknown-schema12-…`; with the stored version 0.8.0 it is `wardroom-0.8.0-schema12-…`, and the log line names 0.8.0 and 0.9.0-SNAPSHOT. After each start the stored version is `0.9.0-SNAPSHOT`, and of the backups in the old and the new name form the two newest are kept. `/licenses/third-party-licenses.txt` is served. The About section shows `0.9.0-SNAPSHOT` and its links.
- Image: the build stage could not be run on the development machine, because Maven cannot download inside a container there; CI builds the same jar. The runtime stage, built with a jar from the host, starts healthy, logs `0.9.0-SNAPSHOT` and serves `/licenses/third-party-licenses.txt`.

README (2026-11-23, as the owner asked): the logo and the tagline at the top are 50 % larger and centered; the tagline is a heading for that, because Markdown has no font size. The feature list starts with the two key features, "Digital archaeology" (jump to date) and "Find the groups that are still alive" (order by activity), followed by the other features. Below it, two screenshots one after the other, with space between them, in `docs/images`: the discover view ordered by activity and an open discussion. They were taken at 1920 × 1080 from a demo instance with made-up groups, authors and articles, with the background work switched off, using headless Edge.

The owner tested the step by hand on 2026-11-23, before the commit.

Left open, none of them blocks the release: the part about the settings in the user guide; the line in the README that says Wardroom is in development (step 17); whether the About section links to the license notices.

**Step 15, activity of a group (2026-11-22)**

Built:

- Migration V13: the two baselines and `articles_per_day` in `newsgroup`.
- The group list refresh keeps the baselines and computes the activity, in the statement that stores a group.
- `GET /api/servers/{id}/groups` has the parameter `sort` (`name` or `activity`), the field `articlesPerDay` of a group and the field `activityKnown` of the page.
- Frontend: the discover view shows the activity in each row, has a select for the order, keeps the order in the address, and shows a hint while no activity is known.
- Spec 003, the data model and the UI concept describe it.

The spec change follows the direction the owner gave in the plan. It was written with the step, without a discussion before. The owner accepted it on 2026-11-22, with these decisions that the direction had left open:

1. **The activity is shown as a number,** for example "240 per day", rounded to a whole number; below one article per day as "under 1 per day". Levels would hide the value and need thresholds that nobody can check.
2. **A group gets both baselines at once,** with the values of the first refresh that lists it. The plan speaks of "the first baseline"; with both set, the rule for the 7 days needs no special case. In the first week the activity is counted from the first load.
3. **The younger baseline changes places first, then the activity is computed.** The count therefore covers 7 to 14 days, as the plan says. After 30 days without a refresh it covers these 30 days once, and the next refresh after 7 days is back to 7.
4. **The hint is shown once above the list,** not in every row, and only while no group of the server has an activity. A single group without a value (new, or numbered anew) shows nothing in its row.
5. **A group that left the server keeps its baselines and its activity.** When it returns, the count goes on from them.
6. **The order is the request parameter `sort`** and part of the address of the page (`?sort=activity`). Changing it shows the first page.
7. **The page says with `activityKnown` whether to show the hint.** It is about the server, not about the search or the page.
8. **An existing installation gets its baselines with the next refresh** after the update, and its first values one day later.
9. **The row has a tooltip** on the activity: "New articles per day, counted over the last 7 to 14 days. Approximate."

Findings:

- A manual refresh shortly after another one changes neither the baselines nor, noticeably, the value. This is what the two baselines are for.
- No index serves the order by activity. The page already counts all matching groups of the server for the total, so the order costs one more pass of the same size. Not measured with a large list.

Verified:

- `./mvnw verify` passes: 622 backend tests and 239 frontend tests.
- Tried by hand on a test instance: the migration ran at the start. With activity values written into the test database, the discover view ordered by activity shows "240 per day", "12 per day", "under 1 per day" and "0 per day" in this order, and the groups without a value last, with nothing in their row.
- Not tried by hand: a refresh that computes a value, because that needs a day between two refreshes of a server. The integration tests move the stored baselines into the past instead. The hint of the first day was not looked at in the browser either; a frontend test covers it.
- The owner tested the feature by hand on 2026-11-22, before the commit. Found there: the order select had moved the time of the last refresh and the refresh button to the left when the row was too narrow. The select is now next to the search box, and the refresh part stays on the right.

**Step 14, cache size limit (2026-11-22)**

Built:

- The measurement of the database size, and the cleanup job `CacheCleanup` in the `articles` module. It runs in the background at the start, after each synchronization run and after the settings were saved.
- The partial index on `article(date)` for articles with content (migration V12).
- The settings `cache.max_size_gb` and `cache.limit_enabled`, and the fields `cacheLimitEnabled`, `maxCacheSizeGb`, `cacheUsedBytes` and `cacheLimitUnreachable` of the settings endpoints.
- Frontend: in the synchronization section the checkbox "Limit the cache size", the field "Maximum cache size", the current use, and the warning when the limit cannot be reached.
- ADR 0034, accepted by the owner on 2026-11-22, and the changes of spec 005, the data model and the UI concept that follow from it.

Decisions. The owner asked for a limit that can be switched off; the three points the plan left open were decided during the step and accepted by the owner with ADR 0034 afterwards. These are the first three points below. The others are open to a veto:

- **The limit is on by default,** with 5 GB. An installation in which nothing was changed behaves as ADR 0023 says.
- **"No limit" is a setting of its own,** `cache.limit_enabled`, and the field `cacheLimitEnabled`. The size keeps its value while the limit is off, and is still required and checked in a request.
- **While the limit is off, the size field is not shown.** The use reads "1.2 GB used", and a hint says that nothing is removed and the cache grows for as long as groups are subscribed.
- **Switching the limit on runs the cleanup at once.** Every save of the settings starts a check in the background.
- **The warning is computed when the settings are read:** the limit is on, the database is above it, and no content is left that may be deleted. While a cleanup is still running, it is not shown.
- **The answer of `PUT /api/settings` has the two read-only values too,** so that the section shows the use against the saved limit without asking again. The use is the one before the cleanup that the save started.
- **The flags of the content are emptied with it** (`body_flowed`, `body_delsp`, `body_truncated`). ADR 0023 names four columns; the flags mean nothing without the text.
- **The use is shown in GB with one decimal, in MB below 1 GB,** and as "less than 1 MB" below that.
- **A manual refresh of one group starts no cleanup.** ADR 0023 says "after each synchronization run"; the run is the scheduled one over all groups.

Findings:

- **The measured size falls only when whole pages become free.** The text of an article that is larger than a page is stored in pages of its own, and these are free at once. Short texts share a page with other data of their article, and deleting them frees nothing that the measurement sees. A cache of mostly short articles can therefore lose all its content before the size is below the limit, and then shows the warning. ADR 0023 accepts that the limit is a target; this is a second reason for it. Not measured on a real database.

Verified:

- `./mvnw verify` passes: 609 backend tests and 234 frontend tests.
- Tried by hand on a test instance: the migration ran at the start, the synchronization section shows the checkbox, the size field and the use ("less than 1 MB of 5 GB"); with the checkbox off and saved, the field is gone, the hint is shown and the use reads "less than 1 MB used".
- Not tried by hand: a cleanup. The smallest limit is 1 GB, and no test database is that large. The integration tests run the job with limits in bytes.
- The owner tested the feature by hand on 2026-11-22, before the commit.
- Not covered by a test: that a synchronization run and the start of Wardroom start the cleanup. The job itself and the save of the settings are covered.

For later steps:

- Step 16: the user guide has no part about the settings yet; the cache size limit and its switch belong there.

**Step 13, separate posting server (2026-11-22)**

Found while the owner tried posting with a real server: the server said that posting is available and refused every post. The provider takes posts only on another host. The owner accepted a separate posting server as a change of specs 002 and 009, with two checkboxes: "Use a separate server for posting" and "Use the same login as for reading".

Built:

- Six columns on `nntp_server` for the posting server (migration V11), its field rules, and its part in the requests and answers of the server endpoints.
- The connection test tests the server and then the posting server. A posting server that fails is named in a successful result.
- A post goes to the posting server on a connection of its own, once.
- Frontend: the two checkboxes and the fields in the server form, the result line for a posting server that cannot be used, the warnings after saving, and the hint about a separate server where posting is not offered or not allowed.
- Specs 002 and 009, the data model, the UI concept and the user guide.

Decisions, open to a veto:

- **The posting server is part of the server,** not a second row. It has no groups, no pool and no circuit breaker.
- **With the login of the server and `USER_PASS`, the posting server must use TLS.** Otherwise the password would be sent unencrypted.
- **The posting server never changes `credentials_invalid`.** A login it rejects is shown in the connection test and when a post fails.
- **A posting password that cannot be decrypted is found when it is used,** not at the start. The start marks only the login of the server.
- **Spec 009 now names the field `postingAllowed`,** as the API does since step 4. This closes the open point of the note below.
- **The hint for "Posting not allowed" links to the server settings** and mentions the separate server.
- **The labels in the form start with "Posting":** "Posting host", "Posting port" and so on, so that each field has its own name.

Verified:

- `./mvnw verify` passes: 594 backend tests and 228 frontend tests.
- The owner posted through a real server by entering the posting host as the host of the server, before this change.
- Tried by hand against two fake NNTP servers, one that refuses posts and one that takes them: a posting server on a wrong port is named in a successful connection test; with the right port a new discussion arrives at the posting server only and shows the "Sent" marker; with the posting server switched off again the post is refused with the hint and the link to the server settings.
- Tried by hand by the owner on 2026-11-22, with a real server: everything works.
- Not tried by hand by the AI assistant: a posting server with a login of its own and the wizard.
- Not covered by a test: a login that the posting server rejects while posting. The connection test with such a login is covered.

**Step 13, posting (2026-11-22)**

Built:

- The `posting` module: the identity endpoints, `POST /api/groups/{id}/articles`, the article builder with RFC 2047 headers, `References` trimming and the flowed body, and the storing of the owner's article.
- The NNTP adapter sends `POST` with CRLF and dot-stuffing and returns the answer of the server as a result.
- `article.posted_from_newsgroup_id`. The owner's article is threaded at once, read, and shown as "Sent" until the synchronization finds it in the group. Threading and the unsubscribe cleanup know these articles.
- Frontend: "New discussion" with its page, "Reply" below each displayed article with the inline editor, quoting of the whole text or of the selection, the identity prompt in the editor, the settings section "Posting identity", the signature checkbox, the warning about quoted text, "Discard draft?", and the hints for each kind of failure with "Try again".
- The connection test message now reads "Posting: available" or "Posting: not offered by this server" with the hint of spec 009.

Deviations and decisions, open to a veto:

- **The kind of failure is the `type` of the Problem Details answer,** as spec 009 says: `"type": "POSTING_FAILED"`. In RFC 9457 `type` is an address; a single word is a valid relative one. The message of the server is `detail`, and `messageId` is an extra field.
- **The connection test field is still `postingAllowed`.** It exists since step 4; spec 009 calls it `postingAvailable`. Renaming it would change an API that works; it was left.
- **A duplicate on a second attempt is recognized by the words of the server.** Spec 009 treats "441 duplicate" as success. Servers word this differently, so an answer `441` that contains "duplicate" or "435" counts. At a first attempt it is a failure.
- **A second attempt for an article that is stored already posts nothing** and returns the stored article. This covers an answer that was lost on the way to the browser.
- **"Try again" sends the failed draft unchanged with its Message-ID.** "Send" after the text was changed sends a new article with a new Message-ID.
- **Limits of the identity:** name 100 characters, email address 254, signature 2,000. Spec 009 names none. The domain of the email address may have letters, digits, hyphens and dots only, because it becomes part of every Message-ID.
- **A parent that names no valid group** is answered in the group it is read in.
- **The 64 KB limit** is for the whole article as it is sent, headers and signature included.
- **"Most of the text is quoted"** means: more lines with text are quotes than not.
- **"Discard draft?" is asked when the draft was changed.** A reply starts with the quoted text; closing it untouched asks nothing. Spec 009 says "an editor that contains text".
- **One reply is written at a time.** While an editor is open, the other "Reply" buttons are disabled.
- **"Reply" stands below the text of an article,** not in the menu "More actions".
- **The synchronization after a post** uses the connection for interactive actions and is left out when the group is being synchronized already. The next scheduled run brings the article then.
- **The thread response has `sent` for each article.** Spec 008 does not list it; spec 009 needs it for the marker.

Verified:

- `./mvnw verify` passes: 581 backend tests and 221 frontend tests.
- Tried by hand against a fake NNTP server that accepts posts: the identity prompt, a new discussion with a name and a subject with other than ASCII (the article arrived with encoded headers, a wrapped long line and the signature), landing on its thread page, a reply with the quoted text, a refusal of the server with its message, and the corrected reply appearing in the thread.
- Tried by hand by the owner on 2026-11-22, with a real server: everything works.
- Not tried by hand by the AI assistant: the "Sent" marker, because the fake server lists a post at once; quoting a selection; "Discard draft?"; "Try again"; a moderated group.
- Not covered by an integration test: the synchronization that starts after a post. Background synchronizations are switched off in integration tests.

For later steps:

- Step 14: the cache cleanup must keep the content of the owner's articles that wait for their location, or they cannot be shown.
- Step 16: an article that is being written when the session ends is lost (see the note on shorter sessions). Moved beyond the MVP with the shorter sessions on 2026-11-23.

**Step 12, ignoring (2026-11-22)**

Built:

- The `ignoring` module: the table `ignore_rule`, the endpoints `GET`, `POST` and `DELETE /api/ignore-rules`, and `IgnoreFilters`, the conditions the other modules build their queries with.
- `article.author_key` with its index. The synchronization and the content fetch store it when they store an article or fill in a stub.
- The thread list leaves out ignored threads and threads whose card article is by an ignored author. "● N new" on a card leaves out articles by ignored authors.
- The unread counts in the sidebar leave out articles by ignored authors, the articles of ignored threads, and the articles of threads that are hidden because of their card article.
- The thread response has `ignored`; each of its articles has `ignoredAuthor`.
- Frontend: "Ignore thread" on the cards and in the header of the thread page, "Ignore author" in a menu of each article, the toast with "Undo", the line "Ignored author · {name}" with "Show", the notice "You ignore this thread" with "Stop ignoring", and the settings section "Ignored".

Deviations and decisions, open to a veto:

- **The author keys of stored articles are filled in at the start, not in the migration.** Spec 010 names a "migration with backfill". Migrations are plain SQL (ADR 0004), and the key needs the rules for addresses in `From`, which SQL cannot express. `AuthorKeyStartup` computes the missing keys at every start, in batches of 1,000; a copy of a database with 101,006 articles took 3.6 seconds, once.
- **The thread response also has `ignoreRuleIds`.** Spec 010 gives it only `ignored`. "Stop ignoring" on the thread page must remove the rule, and the page could not know which one. More than one rule can ignore a thread, when two ignored threads become one; the button removes them all.
- **Ignoring a thread that is already ignored returns the rule that ignores it,** also when the root of the thread has changed since. Otherwise a second rule would be created for the same thread.
- **Labels.** The label of an author rule is the display name, as the thread page shows it; without a name, the address. The label of a thread rule is the subject of the root; when the root is a stub, the subject of the article the owner chose; without any, "(no subject)".
- **The menu of an article** is a button "More actions" with one entry, "Ignore author". Spec 010 speaks of "each article's menu"; none existed. Step 13 adds its actions there or beside it.
- **An article by an ignored author that is shown again** stays shown until the page is left, carries the badge "Ignored author", and is not marked as read. It has no "Ignore author" in its menu.
- **A rule change asks for the loaded thread lists again.** A loaded list is otherwise never asked for again (spec 008); here it must be, because cards appear, disappear or change their count. The list can change its order when a synchronization ran since it was loaded.
- **The toast with "Undo" stays 8 seconds,** twice as long as other toasts.
- **Rules are kept when a server is removed.** They apply in all groups and match by value (data model).
- **Unread counts with rules are slower.** Without any rule, the count is the query it was. With a rule, each group is counted with a query that finds the card article of every thread. For a few dozen groups this is not noticeable; it is asked for every 30 seconds.

Verified:

- `./mvnw verify` passes: 543 backend tests and 197 frontend tests.
- Tried by hand against a fake NNTP server with 500 articles: ignoring a thread from its card (card gone, unread count 500 to 481), the notice on the thread page and "Stop ignoring", "Ignore author" in the menu (the line "Ignored author · Ada Example", unread count 495 to 338, the rule in the settings section), and removing the rule there (count back to 495).
- Tried by hand by the owner on 2026-11-22: everything works.
- Not tried by hand by the AI assistant: "Undo" in the toast and "Show" on an ignored line.

For later steps:

- Step 13: reply buttons go into or beside the menu "More actions" of an article. An article posted with Wardroom needs its author key when it is stored.

**Step 11, discussion view (2026-11-21)**

What was built:

- Backend, module `articles`: the thread list with the three sort orders, the date view and keyset paging; the thread endpoint; "mark thread as read". One query per page computes the cards of a group.
- No migration: the index on `article.thread_root_id` that spec 008 names exists since step 9.
- Frontend: the group view with sort tabs, thread cards, loading of further pages on scrolling, "Load older discussions", the "N new discussions · show" button and the empty states; the date view with its threads up to the chosen day (ADR 0033); the thread page with nesting, collapsing, stubs and crossposts; "Mark thread as read" on cards and on the thread page.
- A new view opens at the top, and going back returns to where the view was left.
- The sidebar marks a group also while one of its threads is open.

Not built, because it belongs to a later step:

- "Ignore thread" on cards and in the thread page header, and "Ignore author": step 12.
- Reply buttons: step 13.

Decisions made during the step, open to a veto. None of them is written into a specification or the data model yet.

Backend:

- **The author key is computed when it is asked for.** Spec 010 stores it in `article.author_key`; that column comes with step 12. Until then the key is derived from `from` for every card and article. The result is the same.
- With `until`, a `rangeId` the group does not have is answered with `400`.
- A cursor belongs to one order. A cursor of another order is answered with `400`.
- `highWatermarkReached` is true when the range is the newest one of the group, as `atNewest` of the extend endpoint. `lowWatermarkReached` is true when the range begins at the stored low watermark. Both are false while the group has no range.
- In the date view, a thread whose card article has no date is not shown: it cannot be "on or before" a day. Spec 008 says so now.
- Order of replies to the same parent: by date, oldest first. Replies without a date follow the dated ones, by article number in the group. Spec 006 says "placed by article number", which does not say where they go between dated replies.
- `read` is false for a stub. The frontend shows no "new" marker on a stub.
- "Mark thread as read" answers `404` for an unknown group and `409` for a group that is not subscribed, as "mark all as read" does. The two reading endpoints answer `404` for both, as spec 008 says.
- The excerpt of a card is computed from the first 20,000 characters of the cached text, so a very long article is not read completely for every card.

Frontend:

- **"N new discussions"** counts the threads that are new or have more messages than shown, among the first 50 threads of the current order. They are asked for aside; the shown list is not touched. A synchronization that changed nothing shows no button. An empty list is loaded again at once.
- **The "new" marker of an article stays while the thread page is open,** although the article is marked as read when it is displayed. Otherwise the marker would vanish before the owner sees it. "Mark thread as read" removes the markers.
- The name shown for an author is the name part of `From`: "Jane Doe" for `Jane Doe <jane@example.org>`. Spec 008 says that the frontend does not parse `from` for the icon; for the name there is no other source.
- "Mark thread as read" on a card is shown only when the thread has unread articles.
- A card shows "latest …" only when the thread has more than one message.
- Further pages load when the end of the list scrolls into view. A button "Show more discussions" is there as well, for the keyboard and for the case that scrolling does not trigger the loading.
- A vertical line belongs to the article whose replies it marks; a click on it collapses that article. A reply deeper than level 6 has the six lines of its topmost ancestors.
- A collapsed article without replies says "hidden" instead of "0 replies hidden".
- The thread page shows the subject once, as its heading: the subject of the first article that is no stub. The single articles show no subject.
- The link back to the group goes back in the browser history when the thread was opened from the list, so the list is where it was left. Opened by its address, it leads to the group view.
- After a stub was fetched, the thread is asked for again, so that the author and date of the article appear.
- After "Load older discussions" the loaded pages are asked for again in place. The list keeps its scroll position.
- The date view says "No discussions up to this day" when its range has none.
- While the jump runs, the date view shows a turning icon with "Searching the server for this day…" above card placeholders. The UI concept prefers skeletons to spinners; the search can take seconds, so the view also says what it waits for, as the first synchronization does. Asked for by the owner.

Findings:

- **Earlier browser checks used `C:\config` as their data directory.** The start command of the AI assistant had a wrong property name (`wardroom.data-directory`), so the application fell back to its default `/config`, which is `C:\config` on this machine. As far as can be told, the directory holds only test data of these checks; its database is from 2026-11-19. It was not changed in this step and not deleted. The owner decides whether it goes. The right property is `wardroom.data-dir`.
- The browser pane of the AI assistant does not draw pages while it is hidden. Loading on scrolling does not happen there, so it was replaced by a stand-in for the check; see below.
- **Found by the owner: the date view showed the group from the chosen day forward.** In a quiet group, a view for 2010 began with discussions from 2012. The owner decided that the chosen day is the upper end: the view shows the discussions that began on or before that day, newest first, with "Load older discussions" as in the normal view. This is ADR 0033, which supersedes the direction of the jump and "load more" in ADR 0021; specs 005 and 008 and the UI concept are changed to match. In the code: the jump takes `until` (the end of the day) and loads the articles up to there; the date search goes on until fewer than 20 numbers remain; the jump result is the range, or the date of the oldest article when the day is before it; "load more" and the direction of the extend endpoint are removed; the address is `?until=YYYY-MM-DD`. Tried in a browser against the local test server: a jump that fetched 500 articles up to the day after 7 probes, a day before the oldest article with its link, and "Load older discussions" down to the low watermark.
- **Found by the owner: going back from a thread to a date view took seconds.** The view forgot its jump when it closed and ran the date search against the server again. Now the jump of a date view is kept as long as its list, 30 minutes, and going back shows the view at once. Only the date view of the last jump is kept per group, because a later jump can join the range of an earlier one with its own. "Load older discussions" in the normal view can also merge the range of a kept date view into the newest range; when the backend then answers that the range is gone, the date view runs its jump again and continues with the new range. Unsubscribing drops the kept lists and jumps of the group. Accepted by the owner on 2026-11-22; spec 008 is changed to match.
- **Found in the review before the pull request: "Load older discussions" in the normal view could fail after a jump to date.** A jump that ends directly below the newest range joins the two, and the joined range has the ID of the jump. A normal view that was loaded before still had the old ID, and its "Load older discussions" answered with an error. Now the list is asked for its range again, and the step is tried once more with that range. A frontend test covers it. Open to a veto: spec 008 does not describe this case.
- **Found by the owner: articles that were stored before step 9 were never threaded.** Every reply was shown as a discussion of its own. Threads were computed only after a synchronization had stored new articles, so a group without new articles kept its articles without a place. A threading that failed was not repeated either, although spec 006 says that the next synchronization retries. Fixed in this step: the start of the application threads every article without a place, and a synchronization that fetches nothing threads the articles of its group that have none. Two tests cover it. Tried on a copy of a database with 2,582 such articles: the start threaded them in 0.7 seconds.
- The thread list of a group with 100,000 articles in 20,000 threads takes 160 ms per page on the development machine, in every order.

Verified and not verified:

- `./mvnw verify` passes: 533 backend tests and 188 frontend tests.
- Tried by hand in a browser, against the built jar with a scratch data directory and a local test NNTP server with 1,200 threaded articles: the wizard; the thread list in all three orders; paging through all pages without a thread shown twice; "Load older discussions"; opening a thread; text, quotes and links of 31 articles; indentation up to level 6; collapsing by line and expanding; the unread count in the sidebar going down; going back to the list at the same scroll position, with the card updated; a thread whose first article the server does not have; the date view.
- Not tried by hand: the loading when something scrolls into view, for further pages and for article text. The browser used for the check did not report scrolling, so a stand-in that reports everything as visible was put in its place. The owner should try this in a normal browser.
- Not tried by hand: the "N new discussions" button, "Mark thread as read", a crossposted article. Component tests cover them.
- Not verified: real articles from a real server.

Left for later steps:

- Step 12: `article.author_key` with backfill replaces the computation in `AuthorKey`; "Ignore thread" and "Ignore author" go where the cards and article rows have their actions; the list and count queries get the ignore filters.
- Step 13: reply buttons on the thread page.
- If real threads show that level 6 is too narrow or too flat: a marker for what a deep reply answers, or a view that starts at a reply.

**Step 10, article reading (2026-11-20)**

What was built:

- Migration V8: the content columns of `article`.
- NNTP adapter: `ARTICLE <message-id>` with the size limit of 10 MB.
- Backend, module `articles`: body extraction with Mime4j, character set fallback, parser limits, omitted parts, yEnc and uuencode blocks taken out of the text, the three endpoints of spec 007, filling in a stub, and the card excerpt function.
- Threading: `In-Reply-To` is read from the stored header block and used when an article has no `References`. Such an article is left out of the subject fallback. An article is threaded again after its content was fetched, when it was a stub or its `In-Reply-To` is known now.
- Content Security Policy and `Referrer-Policy: no-referrer` on all responses.
- Frontend: the text of an article (quotes, signature, links, `format=flowed`, fixed width), the component that loads the content when it scrolls into view and marks the article as read, the author icon, and the leaving page at `/leaving`.

Not visible yet:

- The components for article text, content and author icons are built and tested, but no page uses them before the thread page of step 11. The leaving page can be opened by its address.
- The excerpt function is built and tested; the thread cards of step 11 use it.

Accepted by the owner during the step, and written into spec 007:

- The rule for where a link ends: it runs to the next whitespace, `<`, `>` or double quote; punctuation at the end is cut off; a closing bracket is cut off when the address has no opening one; an address in angle brackets that is broken over up to 5 lines is one link; without angle brackets a link ends at the line end.

- Three columns that the data model did not have: `body_flowed`, `body_delsp` and `body_truncated`. The API returns `flowed` and `truncated`, and the text is unwrapped at display time, so both have to be stored with the text. They are in the data model and in spec 007 now.
- One field more in the API: `delSp`. A sender can mark flowed text with `DelSp=yes`; then the space before a line break is not part of the text. Without the field, such text gets wrong spaces when it is unwrapped. It is in spec 007 now.

Decisions made during the step, open to a veto. None of them is written into a specification or the data model yet:

- `truncated` is true for every limit: text longer than 1 MB, more than 100 parts, nesting deeper than 10, or an article longer than 10 MB. The article then shows one notice, "This article is too long or too complex. Only a part of it is shown."
- An article longer than 10 MB is cut off after the last line that fits. The connection is closed, because the rest of the article is still on its way.
- A character set of `us-ascii` counts as not declared, so the fallback to UTF-8 and windows-1252 applies. Text that claims ASCII often has other characters.
- The first `text/plain` part is shown also when it is marked as an attachment. Spec 007 and ADR 0014 make no exception.
- A part inside an attached message (`message/rfc822`) counts like any other part.
- An HTML part next to a plain text is not named in the attachment notice. Only an article without plain text says "HTML message, not displayed".
- The size of an omitted part is its decoded size. For yEnc it is the size the block names; for uuencode it is computed from the encoded length.
- `PUT /api/articles/{id}/read` answers `204` for a stub and stores nothing. A stub has no text that could have been displayed.
- A stub is filled in with `Subject`, `From`, `Date`, `References` and `Lines` from its headers; `bytes` is the size of the fetched article. It is not marked as read by the fetch; the frontend does that when the text is displayed.
- The server of an article is the server of a group it is located in. A stub has no location; its server is the one of another article of its thread.
- While the credentials are known to be invalid, the content endpoint answers `502` without asking the server, as spec 005 does for synchronizations.
- A failed recomputation of the thread after a content fetch does not fail the request. The content is stored, and the article keeps the place it had.
- Content Security Policy: `default-src 'self'` is added as the base, so that fonts, connections and everything else not named in spec 007 also come only from Wardroom itself.
- The leaving page shows the address as the browser reads it, not the text of the parameter. So what is shown is what "Continue" opens.
- The leaving page is outside the application frame: it has no sidebar, and it is shown also before a server is configured.
- The excerpt is computed from the stored text when it is asked for. It is not stored.
- `NntpHeaderText` in the `nntp` module is public: overviews and fetched articles decode header values the same way.

Findings:

- **An article without a location goes with every unsubscribe cleanup.** The data model deletes "articles without any remaining location". A stub that was filled in by reading it has no location, so unsubscribing from any group removes it and its cached text. It becomes a stub again and is fetched again when it is read. The threads stay correct: the cleanup now also recomputes the threads of such articles. If this is not wanted, the rule in the data model has to change.
- Swagger UI of the development profile works with the Content Security Policy.
- The shell tool of the AI assistant removes one backslash of two in a file written with a here-document. One regular expression was wrong because of that; a test found it. Files with backslashes are written with the file tool since then.

Verified and not verified:

- `./mvnw verify` passes: 514 backend tests and 161 frontend tests.
- Tried by hand in a browser, against the built jar with a scratch data directory: the pages load without a violation of the Content Security Policy; the leaving page shows domain and address, "Continue" has the target as its address, and an address with `javascript:` offers no "Continue"; Swagger UI of the development profile still works.
- Not tried by hand: reading an article. No page shows article text before step 11.
- Not verified: real articles. The extraction is tested with built articles, not with real ones from a server. Step 11 is the first chance to see real text.
- Not verified: an article of more than 10 MB from end to end. The limit is tested in the adapter with a small limit.

Left for later steps:

- Step 11: the thread page uses `ArticleContent`, `ArticleText` and `AuthorIcon`; the thread cards use `Excerpt.of`. Judge quote colors, the collapsed quotes and the notices with real articles. `onRead` of `ArticleContent` is there to clear the "new" marker.
- Step 12: `author_key`, which `AuthorIcon` takes.
- Step 13: articles posted with Wardroom that wait for their location must be excepted from the cleanup (spec 009); the new part of the cleanup that looks at articles without a location needs the same exception.
- Step 14: the cache cleanup removes content; the partial index on `date` for articles with cached content (ADR 0023) is not built yet.

**Step 9, threading (2026-11-20)**

What was built:

- Migration V7: the columns `parent_id` and `thread_root_id` of `article`, with an index each.
- Backend, module `articles`: parsing of `References`, subject normalization, the threading function, the subject fallback, and the service that stores the result.
- Threads are recomputed after every stored chunk of a synchronization, after "load older", "load more" and a jump. After unsubscribing and after a renumbering, the threads that had an article in the group are recomputed; after removing a server, all threads.
- The queries of spec 006: the threads of a group with message count, latest activity and card article, and the thread root of any article.
- Nothing in the frontend. No endpoint is new.

Not built, because it needs a later step:

- `In-Reply-To` is known only when the content of an article is fetched (step 10). The threading function uses it already and is tested with it; nothing passes it in yet.
- The hook "after an article's content is fetched" (step 10).
- The thread list and the thread page that use the queries (step 11).

Decisions made during the step, open to a veto:

- `thread_root_id` of a thread root is the ID of the article itself. An article without a `thread_root_id` has no place yet: new articles are stored that way, and a stub that is filled in loses its thread root. The threading picks up exactly these articles. So a recomputation that failed is repeated by the next one, as spec 006 asks.
- The threading runs in a transaction of its own, after the transaction that stored the chunk. A failure of the threading does not lose the chunk.
- After a chunk, the parent of a stub comes from the article that was stored first, as spec 006 says ("the first article that created it"). A complete recomputation threads by article number. The two differ only when two articles list different Message-IDs before the same missing article, and the newer one was fetched first.
- Unsubscribing and a renumbering recompute only the threads that had an article in the group (accepted by the owner on 2026-11-20; data model and spec 006 are changed). Before the deletion, these threads lose their stubs, and their articles that are also in another group lose their place. After the deletion, every article without a place is threaded, as after a chunk. It runs in the transaction that removes the articles. If it fails, the removal fails with it.
- The unsubscribe cleanup no longer deletes stubs itself. As a safeguard, a stub without a thread root is deleted when threads are recomputed after a removal.
- A complete recomputation deletes all stubs, takes the place away from every article, and threads group by group, lowest group ID first, each by article number. It is used when a server is removed, and a test compares the result of unsubscribing with it.
- Subject fallback: the articles of a group are decided oldest first, so the result does not depend on the order in which they were fetched. Two replies without references attach to the original post, not to each other; without the original, the second attaches to the first.
- Subject fallback: only articles with a date are attached, and only thread roots with a date are matched. A crossposted article is decided in the group with the lowest ID only; otherwise two groups could move it back and forth. A subject that is only a reply prefix matches nothing.
- An article "has no References" when the header has nothing of the form `<…>`. An article whose header has such tokens but none that is usable is a thread root, and the subject fallback does not apply to it.
- Of a Message-ID that appears twice in `References`, the first place counts.
- The card article of a thread without its root in the group is the oldest article by date; articles without a date come last, by article number.
- A link that would close a cycle is logged as a `PROTOCOL_ANOMALY` with the new kind `references.cycle`.
- `parent_id` and `thread_root_id` are foreign keys with `ON DELETE SET NULL`, so the cleanup can delete articles that others point to.
- The threading is in the module `articles`. The groups module asks for the recomputation through its interface `ThreadRecomputation`, which `articles` implements; so `groups` does not depend on `articles`.
- Both indexes of spec 006 are in V7. With this, the migration task of spec 008 for the index on `thread_root_id` is done.

Findings:

- Measured with 100,000 articles in one group, 12 per thread, on the development machine: threading one chunk of 1,000 new articles takes about 65 ms; a recomputation with nothing new, which is the subject fallback of the group, 70 ms; the thread figures of the group 150 ms; a complete recomputation 7.5 seconds.
- A complete recomputation grows with the number of all stored articles, and nothing else can write while it runs. This is why unsubscribing recomputes only the threads of the group. Measured with 100,000 articles in one group and 5,000 in a second one, a tenth of whose threads are crossposted: leaving the small group takes 0.3 seconds, leaving the large one 1.2 seconds, most of it the deletion itself; a complete recomputation of the same data takes 5.4 seconds.
- A thread whose articles were all located in the unsubscribed group, except some replies, keeps those replies: the removed articles become stubs again.

Verified and not verified:

- `./mvnw verify` passes: 459 backend tests and 125 frontend tests.
- The unit tests of spec 006 are in `ThreadGraphTest`, `ReferencesParserTest` and `SubjectFallbackTest`; the integration tests in `ThreadServiceTest` and `ThreadRecomputationTest`.
- Not tried by hand: nothing of this step is visible before step 11.
- Not verified: real articles. Real `References` and subjects are messier than the test data.

Left for later steps:

- Step 10: pass `In-Reply-To` to the threading and leave an article with a known `In-Reply-To` out of the subject fallback; thread a stub that was filled in by a content fetch. Such an article has no location, and the threading finds its articles through their locations, so step 10 needs an entry point by article.
- Step 11: the thread list sorts and pages in SQL; `ThreadRepository.findThreads` is the starting point.
- Step 13: articles posted with Wardroom that wait for their location have none either.

**Step 8, load older, load more, jump to date (2026-11-20)**

What was built:

- Backend, module `sync`: the date search, jump to date, and extending a range downwards and upwards, with `POST /api/groups/{id}/jump` and `POST /api/groups/{id}/ranges/{rangeId}/extend`.
- A group can have several synchronized ranges now. Ranges that touch are merged.
- Articles that these steps store arrive read; a stub they fill in is read as well (ADR 0022).
- Frontend: the date picker in the group header, and the date view at `/groups/{id}?from=YYYY-MM-DD` with its bar, the note about the oldest article and "Load more".

Not built, because it needs a later step:

- "Load older discussions" in the normal group view. The button belongs at the end of the thread list (spec 008), and it needs the ID of the newest range, which no endpoint returns yet. See the first finding. The backend for it exists and is tested.
- The date view shows no threads yet (step 11). Until then it says which article numbers are loaded.
- Threads are not recomputed after a step (step 9).

Decisions made during the step, open to a veto:

- Load more and jump to date fetch nothing above the newest range. What is there is new and belongs to the synchronization, which stores it unread. Otherwise a jump to a recent date would mark new articles as read and leave a gap below them that no synchronization fills. So the date search looks between the low watermark and the end of the newest range, and `atNewest` is true for the newest range of the group.
- A group without any range answers jump with `409`: "This group has not been synchronized yet. Refresh it first." A range from a jump would otherwise count as the newest range, and the next synchronization would fetch everything from there to the high watermark.
- A step waits for a running synchronization of its group, for at most 30 seconds, and then answers `409`. Spec 005 says that they never run at the same time, and not what the second one does. Unlike unsubscribing, a step does not cancel the synchronization.
- A step has a single attempt. ADR 0026 says that direct user actions are not retried.
- A step fetches everything before it stores anything, and stores it in one transaction. With the largest depth these are 10,000 overviews in memory. This is what "nothing is stored" on a failure needs.
- A step does not store the watermarks it reads, and marks nothing as expired. The synchronization does both.
- A renumbered group (high watermark below the end of the newest range) answers `409`: "The server has renumbered this group. Refresh it first." The synchronization does the reset.
- A group the server does not have (`411`) answers `502`. A rejected login marks the credentials and answers `409`.
- A merged range keeps the ID of the range that was extended. After a jump it is the ID of the new range.
- `clampedToOldest` is true when the oldest article is dated after the chosen time. `startDate` is the date of the first stored article from the start on that has a date; it is `null` when there is none.
- The start of a jump is the lower end of the last search interval, as spec 005 says. It lies up to one depth before the first article of the day. In the check by hand, with four articles per day, it was ten days before.
- In the date search, windows that reach the end of the searched numbers without a dated overview count as "nothing on or after the date there". Only five empty windows in a row fail the search. A group whose oldest articles have no date fails it as well.
- A day whose date is in the future cannot be chosen in the date picker.
- Frontend: the note about the oldest article is left out when that article is from the chosen day itself.
- The property `wardroom.synchronization.load-wait` (30 seconds) is how long a step waits for a synchronization. The tests shorten it. It is not documented for people who run Wardroom.

Findings:

- **A gap in spec 008:** the frontend needs the ID of the newest range to offer "Load older discussions" in the normal view, and no endpoint returns it. The page of thread cards has `lowWatermarkReached` and `highWatermarkReached`, but no range. The owner accepted the proposal: the page of thread cards gets a field `rangeId`, the range the list shows. It is written into spec 008 and comes with step 11.
- Spec 005 answers a failed jump or extend with `502`, and until now no endpoint did. `ServerFailedException` in the `sync` module is the first; its text is the cause as the NNTP adapter words it.
- The shadcn/ui component `popover` is new.
- After choosing a day, the tooltip of the date picker button shows again, because the focus returns to the button. This is how the component library behaves; it goes away with the next click or key.

Verified and not verified:

- `./mvnw verify` passes: 382 backend tests and 125 frontend tests.
- Tried by hand in a browser with the built jar against a small local server without TLS, with 3,000 articles over two years: a jump to a day a year ago, "Load more" up to the merge with the newest range, a jump to a day before the oldest article, opening a date view whose start is stored already, and the unread count, which stayed the same.
- Not tried: a real server, a group with millions of articles, a group with wrong or missing dates (only in tests), load older (only in tests, see above).

Left for later steps:

- Recompute threads after load older, load more and jump to date (step 9).
- "Load older discussions" in the normal view, the threads of the date view, and the range ID the thread list needs (step 11).

**Step 7, synchronization core (2026-11-20)**

What was built:

- NNTP adapter: `GROUP`, and `OVER` or `XOVER` with the field order of `LIST OVERVIEW.FMT`. Overview lines are parsed in the adapter: encoded words in `Subject` and `From` are decoded, the date becomes UTC milliseconds.
- Backend, module `sync`: the synchronization of one group (first and incremental, in chunks, with ranges, expiry and renumbering), the schedule, and `POST /api/groups/{id}/sync`.
- Backend, module `settings`: the table `app_setting`, and `GET` and `PUT /api/settings`.
- Migration V6: the columns `subject`, `from`, `date`, `references`, `bytes` and `lines` of `article`, and the table `app_setting` with its three keys.
- Subscribing starts the first synchronization in the background. Unsubscribing cancels a running one. The subscription list says which groups are being synchronized. With this, the two tests of spec 004 that step 6 left open exist.
- The schedule of the group list uses the setting `sync.group_list_interval_hours`.
- Frontend: the refresh button in the group header, and the settings section "Synchronization" with its three fields.

Not built, because it needs a later step:

- Load older, load more and jump to date, with their two endpoints (step 8).
- Threads are not recomputed after a synchronization (step 9).
- The cache size limit: the setting `cache.max_size_gb`, the cleanup job, and the fields `maxCacheSizeGb`, `cacheUsedBytes` and `cacheLimitUnreachable` of the settings (step 14). Until then the settings have three fields.
- The columns of `article` that belong to other specs: `author_key` (spec 010), `parent_id` and `thread_root_id` (spec 006), the content columns (spec 007), `posted_from_newsgroup_id` (spec 009). The step that adds `author_key` has to fill it for the articles that are stored already; spec 010 says so.
- The group view still has only its header. It says "The threads of this group are not shown yet." until step 11.

Decisions made during the step, open to a veto:

- The first synchronization is fetched in chunks of 1,000 as well, oldest first. Spec 005 names chunks only for the incremental one. So a first synchronization with a large depth that is interrupted keeps what it has.
- When the low watermark is above the end of the newest range, the synchronization asks from the low watermark on. The range is extended without a gap. The numbers in between do not exist on the server any more.
- An empty group gets no range. Its next synchronization is a first synchronization again.
- A known article that is not a stub keeps its stored fields. Only a stub is filled in.
- An article number belongs to one article. If another article has a location under the number of a new overview, that location is deleted.
- The manual refresh uses the connection that is kept for interactive actions, and is retried like background work. The first synchronization after subscribing uses a background connection.
- The schedule looks every 30 seconds whether a run is due. The first run is due 30 seconds after the start. When a run was last due is held in memory. A changed interval counts from the time a run was last due.
- A run leaves a server alone whose credentials are invalid, and `POST /api/groups/{id}/sync` answers `409` then. Spec 005 lists "`409` not subscribed or credentials invalid".
- A rejected login (`481`) marks the credentials and stops the run for the server. The group gets no `last_sync_error`; the notice about the credentials says what is wrong.
- A run that finds the circuit of a server open leaves its groups as they are, as spec 005 says. A manual refresh that finds it open writes the error to the group, so the owner sees why nothing happened.
- Subscribing clears `last_synced_at` and `last_sync_error` of the group (left open in step 6).
- Unsubscribing waits for the chunk that is being fetched and stored. In the worst case this is the read timeout of 30 seconds.
- When the server is removed while a group is synchronized, the synchronization notices before it stores its next chunk, and ends.
- Header text outside encoded words is read as UTF-8, otherwise as ISO-8859-1, like group names. A group name is sent to the server as UTF-8.
- `bytes` and `lines` that are not numbers are stored as empty. A date is read leniently; what cannot be read is empty, as spec 005 says.
- `LIST OVERVIEW.FMT` is left out when the capability list has a `LIST` line that does not name it. An answer that does not name all seven fields is logged, and the order of RFC 3977 is used. The order is kept in memory per server, until a restart or until the server is saved.
- `420` in answer to `OVER` counts like `423`: no articles.
- New protocol anomaly kinds: `overview.missing-message-id`, `overview.unusable-format` and `group.malformed-response`.
- A stored setting that is missing, is no number or is outside its range counts as its default.
- The property `wardroom.synchronization.automatic` switches off the schedule and the synchronization after subscribing. It exists for the tests of other features and is not documented for people who run Wardroom.
- Frontend: the refresh button stays usable while a synchronization runs; its icon turns. Pressing it again does nothing, because the backend answers `202` for a running synchronization.
- Frontend: the field for the first synchronization depth says that a change applies to groups subscribed afterwards. Load steps come with step 8.

Findings:

- Apache James Mime4j is a dependency now (ADR 0002 names it). It decodes the encoded words and reads the dates.
- Two column names of the data model, `from` and `references`, are SQL keywords. They are quoted in the migration.
- The groups module and the synchronization need each other. The groups module offers `SubscribedGroups` and expects a `GroupSynchronization`; the `sync` module uses the one and implements the other. So only `sync` depends on `groups`.
- The mock server cannot test a login without TLS. The rejected login is therefore tested without Spring, with replaced collaborators, in `SynchronizationFailureTest`.

Verified and not verified:

- `./mvnw verify` passes: 348 backend tests and 116 frontend tests.
- Tried by hand in a browser with the built jar against a small local server without TLS that knows only `XOVER`: subscribing with the first synchronization, the running icon and the unread count in the sidebar, the failure icon for a group the server does not have, the refresh button with new articles, saving the settings, and a run of the schedule.
- Not tried: a real server, TLS and login, a group with more than a few thousand articles, renumbering and expiry (only in tests).

Left for later steps:

- Recompute threads after each synchronization and after the renumbering reset (step 9).
- Fill `author_key` for stored articles when the column is added (step 12, or step 10 if author icons need it first).
- The cache fields of the settings and the cleanup after each run (step 14).

**Step 6, subscriptions (2026-11-20)**

Accepted by the owner after the review, and written into spec 004: the field `description` of a subscription, and `204` for subscribing to a subscribed group that the server has removed.

What was built:

- Backend: the tables `subscription`, `article`, `article_location`, `read_state` and `synced_range` (migration V5), the four endpoints of spec 004, the unsubscribe cleanup, the unread count and "mark all as read". The group list of spec 003 now says which groups are subscribed.
- Removing the server now also deletes subscriptions, article locations, articles without a location, their read state and the synchronized ranges.
- Frontend: the subscribe toggle in the discover rows and in the group header, the confirmation before unsubscribing, the subscription list in the sidebar, and the group view with its header and "Mark all as read".

A problem in the order of the steps, and how it was handled:

- Spec 004 needs tables that the plan gives to step 7. Its migration task names `read_state`, which points to `article`. Its tests for the cleanup, the unread count and "mark all as read" need `article` and `article_location`, and the cleanup deletes rows of `synced_range`. Spec 005 lists these three tables in its own migration task.
- Handled, open to a veto: step 6 creates all five tables. `article` has only the columns this step uses: `message_id` and `is_stub`. Step 7 adds the other columns of the data model in its own migration. The tests of this step put articles into the database themselves.

Not built, because it needs a later step:

- Subscribing does not start a synchronization, and unsubscribing has none to cancel (step 7). The test "unsubscribing during a running synchronization leaves no locations behind" and the half of the first test that says "starts the first synchronization" come with step 7.
- `sync.running` is always `false`. `lastSyncedAt` and `error` are read from the group row, which nothing fills before step 7. The sidebar already shows the three status icons when the data says so.
- The unread count leaves out ignored articles and hidden threads only from step 12 on.
- The cleanup does not recompute threads (step 9) and does not keep articles that were posted and wait for their location (step 13).
- The group view has only its header. Refresh and jump to date come with steps 7 and 8, the threads with step 11.

Decisions made during the step, open to a veto:

- The subscription in the answer of `GET /api/subscriptions` has a field `description` that spec 004 does not list. The group icon is derived from name and description. Without the field, the icon in the sidebar could differ from the icon of the same group in the discover view.
- Subscribing to a group that is subscribed already is answered with `204` also when the server has removed the group since. Spec 004 lists both "`204` also if already subscribed" and "`409` group removed".
- The cleanup deletes every article without a location, not only those that were in the group. This is what the data model says, and it includes stubs. It reads the whole table `article` once per unsubscribe.
- Unsubscribing leaves `last_synced_at` and `last_sync_error` of the group as they are. Step 7 decides what a new subscription does with them.
- The group view exists only for subscribed groups, and takes its data from the subscription list. No endpoint returns one group, and the threads endpoint of spec 008 answers `404` for a group that is not subscribed. For another group ID the view says so and links to the discover view. So the toggle in the group header can only unsubscribe.
- The badge "Subscribed" in the discover rows is gone; the filled star says the same.
- Frontend: on subscribe, the toggle changes before the backend has answered, and goes back with a message when the backend refuses. The sidebar shows the group after the answer.
- Frontend: a subscribed group that the server has removed gets the badge "Removed from the server" in the group header, next to the icon in the sidebar.
- Frontend: after unsubscribing, a short message confirms it.
- New TestBrowser method `put(path)` without a body.

Found by the owner in the review:

- On a wide screen the subscribe toggle was far from the group name, and the buttons of the group header far from the header. Decided: the discover view and the group view show their content in one centered column of at most 768 px, with the same width whether the sidebar is collapsed or not. The UI concept says so now. Whether 768 px suits article text is judged again when articles are shown (steps 10 and 11).
- The line above the subscriptions in the sidebar reached past the edge of the sidebar. It keeps its side margins now.

Verified and not verified:

- `./mvnw verify` passes: 282 backend tests and 109 frontend tests.- Tried by hand in a browser with the built jar against a small local server without TLS: subscribing in the discover view, the sidebar, the group view, "Mark all as read", and unsubscribing with its confirmation. The articles for the unread count were put into the database by hand.
- Not tried: the sidebar with the status icons for a running synchronization and a removed group (only in tests), a collapsed sidebar, and a real server.

Left for later steps:

- Hand a newly subscribed group to the synchronization, and cancel a running one on unsubscribe (step 7).
- Add the remaining columns of `article` (step 7 and later, as their specs say).
- Recompute threads in the unsubscribe cleanup (step 9); keep posted articles that wait for their location (step 13).

**Step 5, newsgroup discovery (2026-11-20)**

What was built:

- NNTP adapter: `LIST ACTIVE` and `LIST NEWSGROUPS`, both read line by line while the answer arrives, and `MODE READER`.
- Backend: the table `newsgroup` with all columns of the data model, the refresh job, the three endpoints of spec 003 below `/api/servers/{id}/groups`, the search, and the schedule of ADR 0020.
- Frontend: the group icon function with the tables of Group Icons, and the discover view with search, paging, the refresh button, the progress, the time of the last refresh and the error of the last failed one.
- Removing the server now deletes its groups. Other modules do this through `ServerRemovalCleanup` of the server module, in the transaction that removes the server.

Decided by the owner:

- Group Icons contradicted itself. The rule says that topic segments are tried from the last to the first, and `programmer` was a keyword of `code-xml`. The example says that `comp.lang.java.programmer` gets `coffee` through `java`. With the rule, it got `code-xml`. Decided: `programmer` is no longer a keyword and is in the list of ignored segments. The example stays.

Found by the owner while testing with a real server:

- The discover view stayed empty until the refresh button was pressed. The server existed before this step, so nothing had fetched its list yet. The view now fetches a list that was never fetched when it is opened.
- The group icon in a discover row was small next to two lines of text. It is 40 px now; Group Icons says so.
- The log showed an error with two stack traces when the browser closed the connection while a file of the frontend was sent. The error handler now recognizes this case, writes no answer and logs one line at `DEBUG`.
- The descriptions showed the group name. See the finding on descriptions below.
- The search has no index of its own. It reads all groups of the server for every request, once for the page and once for the count. Measured by the owner in the browser against a real server with 111,526 groups: about 90 ms per search. Decided: good enough for now. If it gets too slow, the first step is to count only when the search text changes, the second a full-text index in a new migration.

Decisions made during the step, open to a veto:

- `MODE READER` (left open in step 2): it is sent once per connection, before the first reading command, unless the capability list names `READER`. `200` and `201` confirm it; a server that does not know the command is read anyway; `502` and `480` fail the action. The connection test does not send it, so its commands are unchanged.
- `LIST NEWSGROUPS` is left out only when the capability list has a `LIST` line that does not name `NEWSGROUPS`. A list without a `LIST` line may be incomplete (finding of step 4), and a server without a capability list is asked as well. Spec 003 says "unless `CAPABILITIES` shows the server does not support it".
- The interval of the scheduled refresh is fixed at 24 hours, the default of `sync.group_list_interval_hours`. The setting itself and the table `app_setting` come with the settings section "Synchronization" in step 7.
- The schedule looks once a minute for group lists that are due. After a restart, the time of a failed refresh is not known any more, so a list that is due is fetched at the first look.
- The schedule leaves a server alone while its credentials are invalid. Every attempt would be another rejected login.
- Every failed refresh is followed by a scheduled attempt one hour later, also a failed manual one. ADR 0020 names the failed scheduled refresh.
- Response `480` (login required) to a reading command is reported as "the server refused", with the message "The server requires a login". It does not mark the credentials as invalid; that stays reserved for a rejected login.
- The search text may have at most 200 characters; a longer one is answered with `400`. Spec 003 has no limit.
- `page` below 0 and `size` outside 1 to 100 are answered with `400`, with field and rule. Spec 003 lists only `200` and `404` for this endpoint.
- `groupsReceived` in the refresh status keeps the number of the last refresh after it has ended, and is 0 after a restart.
- `subscribed` is `false` for every group until step 6.
- A group that the server lists twice is stored once. A description without text does not replace a stored description. A description for a group that is not in the group list is ignored.
- Group names are decoded like descriptions: UTF-8, otherwise ISO-8859-1.
- New protocol anomaly kinds: `list.malformed-active-line`, `list.malformed-newsgroups-line` and `list.malformed-description`.
- The property `wardroom.group-list.automatic-refresh` switches off the refresh for a new server and the schedule. It exists for the tests of other features, which would otherwise find unexpected connections on their mock servers. It is not documented for people who run Wardroom.
- Frontend: search text and page are part of the URL (`/discover?q=java&page=2`), so the back button and bookmarks work. The search starts 300 ms after the last key press.
- Frontend: a group shows the badge "Moderated" or "No posting"; a group that allows posting shows none. The subscribe toggle comes with step 6.
- Frontend, asked for by the owner in the review: when the discover view is opened and the group list was never fetched, the view starts the fetch itself. It does not do so after a failed fetch or while the credentials are invalid; then the owner presses the button. Before, a server that existed already had no list until the schedule looked, one minute after the start.
- Frontend: while a refresh runs, the status is asked for once a second. The list on the page is loaded again when the refresh ends, not while it runs.
- Group icons: a name that consists of a country hierarchy alone, such as `de`, keeps it as its hierarchy.

Findings:

- A transaction that read first and wrote later failed when another request had written in between (`SQLITE_BUSY_SNAPSHOT`). SQLite does not wait in that case. The test with 100,000 groups found it: marking removed groups collided with the session update of a status request. Every transaction now takes the write lock when it begins and waits for it up to the busy timeout. This also covers adding a server, which reads before it writes.
- A reader of the NNTP library ends quietly when the connection closes in the middle of a multi-line answer. A list cut short would look complete, and groups would be marked as removed. The adapter reads the lines itself and reports a lost connection.
- A real server sent no real description for any of its groups. For most groups the description was the group name; for about 1,900 groups of the classic hierarchies it was the rest of a `LIST ACTIVE` line, such as `0000015957 0000000001 y`. Decided by the owner: both count as no description, and the second is logged as a protocol anomaly. Spec 003 says so now. The raw lines were not kept, so that the server sent them this way is concluded from the stored data, not seen on the connection.
- Lucide has renamed four icons of the keyword table: `circle-help` to `circle-question-mark`, `laugh` to `face-grinning`, `music` to `eighth-notes`, `waves` to `waves-horizontal`. The table in Group Icons has the new names.
- The server service and the cleanup of the groups module need each other, so the service looks the cleanups up when a server is removed, not when it is created.
- New frontend component from shadcn/ui: `badge`.

Left for later steps:

- The subscribe toggle in the discover rows, and `subscribed` in the group list (step 6).
- The setting `sync.group_list_interval_hours` and its field in the settings section "Synchronization" (step 7).
- The quick switcher searches the discover view (step 16). Moved beyond the MVP on 2026-11-23.

Not verified:

- By hand, the feature was tried with the built jar against a small local server without TLS: 8,400 groups, a list that breaks off, and a list with half of the groups. The refresh against a real server was tried by the owner, not by the author of the code.
- The contrast of the twelve circle colors against the white symbol was calculated from the color values, not measured in a browser. The lowest value is about 3.2:1.
- The image build.

**Step 4, server configuration and first-run wizard (2026-11-20)**

What was built:

- Backend: the five endpoints of spec 002 below `/api/servers`, the field rules, the connection test, and the removal. The table `nntp_server` has all columns of the data model; the ones for the group list and the posting identity are filled by later steps.
- Credential encryption: the key file in the data directory, `WARDROOM_ENCRYPTION_KEY_FILE` (ADR 0025), AES-256-GCM with the versioned format, and every row of the table "Missing or unusable key".
- Creating the account deletes the NNTP username and password and sets the authentication to "none" (ADR 0017). The server module does this through `AccountCreationCleanup`.
- Frontend: wizard step 2, shown at every URL while no server exists; the settings section "Server" with the connection test, the save warning and the removal with its confirmation; a notice on every view while the credentials are invalid.
- The logo in the sidebar is 50 % wider than before (requested by the owner). The collapsed sidebar keeps the small logo, because its column is too narrow for a bigger one.
- Other modules get two things from the server module: `NntpServers`, which returns the settings for the NNTP adapter and marks credentials as invalid, and the event `ServerCreated`.

Decisions made during the step, open to a veto:

- `CONNECTION_LOST` in the connection test (left open in step 2): a server that closes the connection during the test is reported as `SERVER_REFUSED`. Spec 002 keeps its seven failure types. The other choice is an eighth type in the spec.
- The connection test result has a third field, `postingAllowed`. Spec 002 requires that the result reports this but shows no field for it. Posting is offered when the capability list names `POST`. A list that names `READER` but not `POST` means no posting. In every other case the greeting decides (`200` or `201`).
- The connection test of the form answers `404` when the given `id` does not exist. Spec 002 lists only `200` and `400` for this endpoint.
- Where the password is decrypted. Spec 002 says only the NNTP adapter decrypts. Since step 2 the adapter takes the password in clear text in its settings. The server module therefore decrypts at the moment it builds these settings, and nobody keeps them. Following the spec literally would move the key into the NNTP module.
- Credentials are marked as invalid in three cases: the server answers `481` to the test that runs with saving; another module reports a `481` through `NntpServers`; the stored password cannot be decrypted. The mark is cleared when new credentials are saved, and when a test that runs with saving logs in successfully. The test of the form alone, without saving, never changes the mark.
- After the key file was lost, the encrypted password stays in the database, although it cannot be read. The owner enters a new one, which replaces it.
- Username and password of the server must not contain control characters. A line break in them would start a new NNTP command. Spec 002 only has the length rule.
- Host: letters, digits, dot, hyphen, underscore and colon. This covers host names, IPv4 and IPv6 addresses. Other characters are refused.
- The username of the server is stored as entered, without trimming. Name and host are trimmed.
- The group list fetch of step 5 starts in answer to the event `ServerCreated`. The event is published inside the transaction that stores the server.
- Two requests at the same time cannot both add a server; they are handled one after the other.
- In the frontend, "TLS mode" and "Authentication" are plain select fields, because each has two values.
- The mistakes in username and password of the form are shown once the other fields are valid, not together with them.
- After the server was removed, the server form is shown with the step indicator of the wizard, like at the first run.

Findings:

- On the development machine, a connection test with `localhost:119` was answered with `400` although nothing of Wardroom listens there. The security software of the machine handles that port. This does not affect Wardroom.
- Found by the owner with a real server: its capability list names only `VERSION 1` and `AUTHINFO USER PASS`, although it offers reading and posting. A capability list can therefore be incomplete. The posting rule above was changed for this. Step 5 must not rely on the list either when it decides about `MODE READER`, `LIST` and `OVER`.
- A toast stays as long as the mouse is over it. In a narrow window it covered the "Remove server" button at the right edge, so the button is now next to the other two.

Left for later steps:

- Removal deletes the row in `nntp_server`. Groups, subscriptions, article locations, articles and read state do not exist yet; the steps that create these tables (5, 6 and 7) add their deletion and the tests "removal deletes all server data" and "editing host or port keeps subscriptions and cache". For now a test shows that editing keeps the row with its other columns.
- The group list fetch itself (step 5). A test shows that adding a server publishes `ServerCreated` and editing does not.
- Marking credentials as invalid after `481` during synchronization (step 7) calls `NntpServers.markCredentialsInvalid`.
- Documentation of spec 002 for the user guide (step 16).

Not verified:

- A connection test against a server with TLS and a login was not tried by hand, only in the integration tests with the mock server. By hand, the test was tried against a small local server without TLS, and against a closed port.
- The permissions `0600` of the key file. The development machine runs Windows, where the test for them is skipped; it runs in CI on Linux.
- The image build.

**Step 3, authentication and application shell (2026-11-19)**

What was built:

- Backend: the six endpoints of spec 001 below `/api/auth`, the access rules, sessions in the database, login throttling, and the account creation with its cleanup. Spring Session JDBC works with SQLite; no session store of Wardroom's own was needed.
- Frontend: login page, wizard step 1 (account), the shell with the sidebar, the settings page with its section navigation, the "Account" section with the password change, log out, toasts and skeletons. The logo and the favicon are in place.
- A module that stores sensitive data implements `AccountCreationCleanup` of the `auth` module. Its cleanup runs in the transaction that creates the account (ADR 0017).

Decisions made during the step, open to a veto:

- Username: surrounding whitespace is removed at setup and at login. Apart from that, the username is stored as entered and compared exactly, including upper and lower case. Spec 001 says "stored as entered" and "after trimming"; this is how both were read.
- Lengths of username and password are counted in characters, not in bytes. An emoji is one character.
- Changed in the review: a session ends after 7 days of inactivity instead of 30, and the login page has the option "Remember me". Without it the session cookie ends with the browser session; with it the browser keeps the cookie and the server enforces the 7 days. Before, there was no such option and every cookie was kept for 400 days. Spec 001 was changed accordingly.
- Also decided in the review, for step 16: a session without "Remember me" will end after 30 minutes of inactivity. Until then it ends after 7 days like every session. Moved beyond the MVP on 2026-11-23; every session keeps the 7 days.
- A login always gets a new session; a session the browser had before is ended.
- Logins are checked one after the other, so parallel attempts cannot get around the throttling.
- A password that is too long to be valid is answered like a wrong password (`401`), not with `400`.
- A wrong current password at the password change is not throttled. Spec 001 only throttles the login.
- Changed in the review: a password change ends all sessions, including the current one, and the owner logs in again. Before, the current session stayed logged in, and a copy of its session ID in other hands would have stayed valid. Spec 001 was changed accordingly.
- Validation errors of all endpoints have one shape: `detail` is the first failed rule, and `errors` lists every failed field with its rule.
- Files below `/assets` may be kept by the browser for a year, because their names change with their content. Everything else is checked with the server on every page load.
- The settings navigation shows all six sections of the UI concept. A section whose feature is not built yet says so.
- `/` leads to the discover view, which has only its heading so far.
- After wizard step 1, the application is shown directly. Step 4 adds wizard step 2 and the rule that the application needs a server.
- The sidebar collapses to a narrow column of icons and remembers this in a cookie. In a narrow window it opens over the page.

Findings:

- jOOQ maps `INTEGER` columns to 32-bit numbers. Columns for times in milliseconds or for article numbers must be declared `BIGINT` in the migrations. The session tables do this.
- Spring Boot would create a second user with a generated password and print that password in the log. This is switched off.
- The attributes of the session cookie (`HttpOnly`, `SameSite`, `Secure`) are set by the web server, so they are tested with a real server in `SessionRestartTest`, not with the usual integration tests.
- The logo files are copied into `frontend/src/assets`, because the image build does not include `assets/`. The favicon in `frontend/public` is derived from the logo.
- The build no longer prints `[ERROR]` lines when nothing failed: pnpm is started with `--silent`, which removes the line that repeats its command. A real failure of the frontend checks still shows its messages and ends with `BUILD FAILURE`.
- New dependencies: Spring Security, Spring Session JDBC, Bouncy Castle (needed for Argon2), and in the frontend the toast library of shadcn/ui.

Left for later steps:

- The test "setup after the account row was deleted" covers the sessions and that cleanups run. The part about the NNTP credentials is tested in step 4, when they exist.
- Installation documentation of spec 001 (step 16): complete setup immediately; an update may require logging in again.

Not verified:

- That forwarded headers from an address outside a private network are ignored. This is the web server's default behavior and was not tested; the test only shows that a local address is trusted.
- The image build. The application was tried in the browser with the jar built by Maven.

**Step 2, NNTP foundation (2026-11-19)**

Library spike (open question 1). Apache Commons Net 3.13.0 is sufficient; nothing has to be replaced. The findings are kept as tests in `CommonsNetSpikeTest`.

| Command | Result |
|---|---|
| `CAPABILITIES` | No method. Works as a generic command; the adapter reads the multi-line answer itself |
| `LIST ACTIVE` | Method exists and streams: groups are handed out while the list is still arriving, with one line of look-ahead |
| `LIST NEWSGROUPS` | No method. Works as a generic command |
| `LIST OVERVIEW.FMT` | Method exists |
| `OVER` | No method; the library's overview method sends the older `XOVER`. `OVER` works as a generic command |
| `POST` | Method exists, with dot-stuffing |
| Character data | The library reads ISO-8859-1, so every byte survives and can be decoded later with the right character set |

- No check against a real server was made. Everything was verified against the mock server only.

What was built:

- Other modules use two interfaces of the `nntp` module. `NntpConnections` runs work on pooled connections, with the connection limit, the circuit breaker and retries. `NntpConnector` opens one dedicated connection without them, for the connection test. Both hand out an `NntpClient`, which so far offers the capabilities and the posting flag of the greeting. Later steps add the commands.
- The mock NNTP server is `MockNntpServer` in the test sources, with `MockReply` for scripted answers and faults. Fixtures are in `src/test/resources/nntp`.

Decisions made during the step, open to a veto:

- The capabilities are requested again after the login, because a server may offer more to an authenticated client (RFC 3977).
- The circuit breaker counts every attempt, not every request. With 3 attempts per request, the circuit opens during the second failing request. An open circuit ends the retries of the running request.
- A certificate failure counts toward the circuit breaker but is not retried. A rejected login and a protocol violation are neither retried nor counted.
- Response `482` at login is treated like `481`, because older servers use it for rejected credentials.
- An unused connection is kept for 60 seconds and then replaced. A connection on which an action failed is closed. Changed server settings always get a new connection.
- The adapter refuses settings with credentials but without TLS, in addition to the validation of spec 002.
- Background work waits without a time limit for a free connection; only interactive actions have the 30 second limit.
- The adapter has three failure types that spec 002 does not list: `CONNECTION_LOST`, `CIRCUIT_OPEN` and `NO_CONNECTION_AVAILABLE`. Step 4 has to decide how the connection test reports `CONNECTION_LOST`.
- New protocol anomaly kinds: `response.malformed-line` and `response.unexpected-code`. After such a response the connection is closed.

Left for later steps:

- `MODE READER` is not sent yet. To be decided in step 5, with the first reading command.
- Marking the server's credentials as invalid after `481` belongs to the server module (step 4). The adapter reports the failure type `AUTH_FAILED` and does not retry.

Tests and build:

- The certificates for the TLS tests are created when the tests run, with the `keytool` of the JDK. No key is stored in the repository.
- The test client trusts the certificate it sees when it connects to the mock server. On the development machine, security software intercepts TLS even for local connections and re-signs the certificate; the tests pass there through that interception.
- `.gitattributes` now forces LF line endings in every checkout. On Windows, a fresh checkout had CRLF, and the frontend format check failed.

**Step 1, project skeleton (2026-11-19)**

- A tenth module, `platform`, holds the technical code all modules share: data directory, database startup, logging helpers, API error handling. The generated jOOQ classes are an open module of their own.
- The first migration creates `wardroom_user`, so that the code generation and the startup tests have a real table. Step 3 uses it.
- A new, empty database is migrated without a backup; a backup is written only when an existing database has pending migrations.
- `WARDROOM_LOG_LEVEL` applies to Wardroom's own output. Libraries follow it only when it is stricter than `INFO`; at `DEBUG` they stay at `INFO`.
- Requests that fail with a `500` are logged with method, path and stack trace. Other failed requests (`4xx`) are not logged.
- The accent color is the blue of the logo, `#0374cf`.
- The runtime image installs `curl`, because the base image has no tool for the `HEALTHCHECK`.
- The frontend uses React Router 7 and TypeScript 5.9, not the newest major versions: ADR 0007 names React Router 7, and the API client generator requires TypeScript 5.
- Not verified locally: the build stage of the Dockerfile. The runtime stage was checked with the jar built by Maven. To be checked on the first build outside the development machine.
- When committing: mark `mvnw` and `docker/entrypoint.sh` as executable in Git (`git update-index --chmod=+x`), otherwise the CI workflow cannot start the wrapper.
