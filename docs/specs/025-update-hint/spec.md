# 025. Update hint

- Status: Accepted
- Date: 2026-11-24
- Related: [ADR 0009](../../architecture/decisions/0009-packaging-and-deployment.md), [ADR 0032](../../architecture/decisions/0032-application-version-in-backup-names.md), [spec 018](../018-notifications/spec.md)
- Changes when implemented: the settings section "About" in the UI concept

## Scope

Wardroom tells the owner when a newer version is released. It never updates itself. Part of step 25 of the [implementation plan](../../implementation-plan.md).

## Decisions

Asked for by the owner on 2026-11-24. Proposed by the AI assistant in the implementation plan and approved by the owner with this spec:

1. The backend asks GitHub once a day for the latest release of the project and compares its version with its own. Pre-releases are ignored, and a development build does not check.
2. The browser is not involved, so the Content Security Policy stays as it is.
3. The hint is quiet: a mark on "Settings" in the sidebar and a line in the settings section "About", with a link to the release notes. No dialog and no toast. It is shown only after login.
4. Wardroom never updates itself. The user guide explains the update.
5. A setting turns the check off; it is on by default. The user guide says what the check sends.
6. "Dismiss" hides the hint until the next version.
7. A failed check is logged once and tried again the next day, without a message in the browser.
8. Once notifications exist ([spec 018](../018-notifications/spec.md), after 1.0), a new version can also be sent there. Not part of this spec.

Added by the AI assistant, open to a veto by the owner:

9. The first check runs 5 minutes after the start, so a restart loop does not send a request each time, then every 24 hours.
10. The link to the release notes is built by Wardroom from the version, not taken from the answer of GitHub.

## User stories

- As the owner, I learn that a new version exists without reading the project page.
- As the owner, I turn the check off, because my installation should not contact anything but my NNTP server.

## Functional requirements

**The check**

- With the setting on, the backend asks for the latest release 5 minutes after the start and then every 24 hours.
- The latest release is the newest release that is neither a pre-release nor a draft.
- A newer version is available when the version of the release is higher than the running version, compared as `major.minor.patch`.
- A development build ("Development build" in the section "About") does not check.
- The result is kept in memory. After a restart, the hint returns with the first check.

**The hint**

- When a newer version is available and not dismissed:
  - The settings button in the sidebar header has a small dot, with the tooltip "Settings · Wardroom {version} is available".
  - "About" in the section navigation has the same dot.
  - The section "About" shows, below the version: "Wardroom {version} is available." with the link "Release notes" (opens in a new tab) and a button "Dismiss".
- "Dismiss" hides the dots and the line until a still newer version is released. The dismissed version is stored on the server, so it applies in every browser.
- The section "About" links to the part of the user guide that explains the update.

**The setting**

- "Check for new versions" in the section "About", on by default, with the explanation: "Once a day, Wardroom asks GitHub for the latest release. It sends the version of Wardroom and nothing else." The section gets a save button, as every settings section.
- Turning it off stops the checks at once and hides the hint. Turning it on checks within 5 minutes.

## What the check sends

- One HTTPS request: `GET https://api.github.com/repos/stekhub/wardroom/releases/latest`, with the header `User-Agent: Wardroom/{version}`. No cookies, no token, nothing about the owner, the server or the groups.
- GitHub sees the address the request comes from, as with any request on the internet. The user guide says so.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/update` | | `200 { "availableVersion", "releaseNotesUrl" }`: both `null` when no newer version is known, or it was dismissed, or the setting is off |
| `POST /api/update/dismiss` | `{ "version": "1.1.0" }` | `204`; `400` not a version |
| `GET`, `PUT /api/settings` | as in spec 005, plus `update.checkEnabled` (boolean, default `true`) | as in spec 005 |

- Both endpoints need a session; `POST` needs the CSRF token.
- `releaseNotesUrl` is `https://github.com/stekhub/wardroom/releases/tag/v{version}`.
- The frontend asks for `GET /api/update` after login and when the settings page is opened; it is not polled.

## Technical design

- A new module `updates`, with a scheduled job that uses Spring's `RestClient` with a timeout of 10 seconds for connecting and for reading.
- Only `tag_name` is read from the answer. It must match `v{major}.{minor}.{patch}` with numbers; any other answer counts as a failed check. `prerelease` and `draft` are checked as well, although GitHub does not return them for the latest release.
- The address of the releases is a configuration property, so tests point it to a local test server. In integration tests the check is off (`wardroom.update-check.automatic=false`), as the other background jobs.
- The running version comes from the build information that `GET /api/about` uses.

## Data model changes

- `app_setting` gets the keys `update.check_enabled` (default `true`) and `update.dismissed_version` (no default).

## Error handling

| Situation | Behavior |
|---|---|
| No connection, timeout, an HTTP error, a limit of GitHub reached, or an answer that cannot be read | One `WARN` line with the reason; the next failures are logged at `DEBUG` until a check succeeds. The next check is in 24 hours. The last known result stays |
| No release at all (`404`) | No newer version; not logged as a failure |
| The running version is newer than the latest release | No newer version |

## Security considerations

- The answer of GitHub is untrusted. Only the version is used, after the check of its form; no text of the answer is shown or logged without escaping.
- The link is built by Wardroom, so the answer cannot lead the owner elsewhere.
- Nothing is downloaded or run.

## Testing requirements

- Version comparison: higher major, minor and patch; equal; older; a development build does not check.
- Answers of a local test server: a valid release, a pre-release, `404`, `500`, a timeout, a `tag_name` of the wrong form, an answer that is not JSON.
- Failures are logged once until a check succeeds.
- `GET /api/update`: newer version, dismissed version, a still newer version after dismissing, the setting off.
- The setting is stored and stops the checks.
- Frontend: the dots on the settings button and on "About", the line with "Release notes" and "Dismiss", hidden after dismissing; the setting with its save button.

## Implementation tasks

1. Module `updates` with the scheduled check, the version comparison and the endpoints; the setting; update `openapi.json`.
2. Frontend: the dots, the line and the setting in the section "About".
3. Update the UI concept, the data model and the user guide, with what the check sends and how to update.
