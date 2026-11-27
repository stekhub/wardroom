# 026. Windows app

- Status: Accepted
- Date: 2026-11-26
- Related: [ADR 0036](../../architecture/decisions/0036-windows-app.md), [ADR 0037](../../architecture/decisions/0037-key-protection-with-dpapi.md), [ADR 0017](../../architecture/decisions/0017-password-reset.md), [ADR 0028](../../architecture/decisions/0028-backup-retention.md), [spec 025](../025-update-hint/spec.md)
- Changes when implemented: the README, the user guide, `CONTRIBUTING.md` and the release workflow

## Scope

Wardroom on Windows 10 and 11 without Docker: the desktop mode, the window, the icon in the notification area, the installer and winget. Step 26 of the [implementation plan](../../implementation-plan.md), the first step after 1.0.

## Decisions

Decided by the owner on 2026-11-23 and 2026-11-26:

1. The same application as the image, with a desktop mode on top, turned on by one switch that only the installer sets (ADR 0036).
2. A web server on `127.0.0.1` only, with a check of the `Host` header, and the login kept (ADR 0036).
3. The key is protected with DPAPI in the desktop mode, and stays a key file everywhere else (ADR 0037).
4. The window is Edge in app mode; without Edge, the default browser.
5. An icon in the notification area with "Open", "Start with Windows" and "Quit". "Start with Windows" is off by default.
6. An MSI with the Java runtime, installed per user, through winget and as a download on the release page. Not signed at first.

## Details added by the AI assistant

Open to a veto by the owner:

1. The port is picked once and used again, so what the browser stores for the window is kept (ADR 0036).
2. The window has a profile of its own in the data folder, without the extensions and history of the owner's Edge (ADR 0036).
3. The log is also written to files in the data folder, because nobody sees the standard output (ADR 0036).
4. Closing the window does not quit Wardroom; it keeps synchronizing in the notification area. The first time, a message says so. "Quit" in the icon's menu ends it.
5. A second start opens a window of the running Wardroom instead of starting it twice.
6. The icon's menu has "Reset account…", because a Windows computer usually has no SQLite tool for the reset of ADR 0017.
7. The update hint of spec 025 shows the winget command in the desktop mode.

## User stories

- As the owner, I install Wardroom on Windows with one command and open it from the Start menu, like any other program.
- As the owner, Wardroom keeps my groups up to date while my computer is on, if I want it to.
- As the owner, a backup of my user folder does not give away my NNTP password.

## Functional requirements

**Installing and updating**

- `winget install stekhub.Wardroom` installs Wardroom for the current user, without an administrator prompt. `winget upgrade stekhub.Wardroom` updates it.
- The MSI from the release page installs the same way when it is opened. It asks for the folder and the shortcuts; winget installs silently with the defaults: a Start menu entry and a desktop shortcut.
- A new version replaces the old one. Wardroom must be quit first; the installer asks for it when it is still running.
- The data folder is `%LOCALAPPDATA%\Wardroom`. Uninstalling leaves it in place, so a new installation finds the data again. The user guide says how to delete it.
- Backups before migrations work as in the image (ADR 0028), in the data folder.

**Starting**

- Opening Wardroom starts it and shows a short start page in the window until the application is ready, then the login or the setup wizard.
- A second start, while Wardroom runs, opens a window of the running application and exits.
- Wardroom finds the running application through a file in the data folder with its port and process ID, and checks that the port answers as Wardroom. A file left behind by a crash is ignored.

**The window**

- Edge in app mode at `http://127.0.0.1:{port}`, without an address bar or tabs, with the Wardroom icon and the profile of the data folder.
- Without Edge, the default browser opens the same address.
- Closing the window leaves Wardroom running. The first time, a message of the icon says "Wardroom keeps running in the notification area. Quit it there."

**The icon in the notification area**

- Its menu: "Open", "Start with Windows" (a check mark), "Reset account…", "Quit".
- "Start with Windows" starts Wardroom without a window when the owner logs in to Windows. It is stored in the registry of the current user, under `Run`.
- "Reset account…" asks "Delete the Wardroom account? You then create a new one. Your groups, articles and read state are kept; the NNTP login and two-factor authentication are removed." On confirmation, it does the reset of ADR 0017 and opens the window at the setup wizard.
- "Quit" stops synchronization, closes the database and ends Wardroom.

**Desktop mode in the application**

- The rules of ADR 0036 and ADR 0037: `127.0.0.1` only, the `Host` check, the port, the log files, the key with DPAPI.
- The settings section "About" shows "Windows app" next to the version.
- The update hint (spec 025) adds "Update with: winget upgrade stekhub.Wardroom".

## Release workflow

- The workflow of the image stays as it is. A second job on a Windows machine of GitHub builds the MSI from the same jar with `jpackage`, starts it once in the desktop mode as a check, and attaches it to the release.
- Then `wingetcreate update stekhub.Wardroom` opens a pull request for the new version in Microsoft's package repository, with the token stored as a secret of the repository.
- When the winget step fails, the rest of the release is not affected. `CONTRIBUTING.md` says what to do.
- Once, by hand: `wingetcreate new` with the first MSI creates the package `stekhub.Wardroom`.

## API contracts

- `GET /api/about` gains `windowsApp` (boolean).
- No other endpoint. The icon's menu works in the process, not over HTTP.

## Technical design

- A module `desktop` holds the switch, the port, the window, the icon and "Start with Windows". Other modules do not depend on it, apart from the kind of key protection in the `platform` module (ADR 0037).
- The `Host` check is a filter before Spring Security, active only in the desktop mode.
- The icon uses `java.awt.SystemTray`. The registry and DPAPI are reached through JNA.
- The log files use the logging of Spring Boot with a rolling file, active only in the desktop mode.
- `jpackage` builds the MSI with `--win-per-user-install`, `--win-upgrade-uuid` (fixed), `--win-menu`, `--win-shortcut`, `--win-dir-chooser` and `--win-shortcut-prompt`, and the launcher with `-Dwardroom.windows-app=true`.

## Data model changes

None. The data folder gets the files `desktop.port`, `credentials.dpapi`, `logs\` and `window\` (the profile of the window).

## Error handling

| Situation | Behavior |
|---|---|
| The switch is set on another system than Windows | Wardroom does not start; the log names the reason |
| `WARDROOM_ENCRYPTION_KEY_FILE` is set in the desktop mode | Wardroom does not start; the log names the reason (ADR 0037) |
| The key cannot be decrypted, for example after moving the data folder | A new key is created, as for a lost key file (ADR 0025, ADR 0035); the log says so |
| The stored port is taken | Another free port is picked and stored; the log says so |
| A request with another `Host` | `421`, logged at `DEBUG` |
| Wardroom does not start within 60 seconds | The start page says so and names the log folder |

## Security considerations

- Loopback only and the `Host` check (ADR 0036). The login stays.
- The key is protected with DPAPI (ADR 0037).
- "Reset account…" is reachable only from the owner's own Windows session, like a tool on the owner's own computer.
- The window's profile is kept apart from the owner's Edge.

## Testing requirements

- Unit tests: the `Host` check (allowed and refused values, also with another port); picking and reusing the port; finding a running application and ignoring a stale file.
- Integration tests: the desktop mode refuses another `Host`; `GET /api/about` reports it; the switch on another system and `WARDROOM_ENCRYPTION_KEY_FILE` stop the start. The DPAPI calls are tested on a Windows runner only.
- An unprotected `credentials.key` is protected and deleted, and the stored password is still readable.
- The release workflow starts the built MSI once in the desktop mode and checks that it answers.
- By hand, on Windows 10 or 11: installing with the MSI and with winget, the window, the start page, a second start, closing and the message, "Start with Windows", "Reset account…", "Quit", an update over a running version, uninstalling.

## Implementation tasks

1. The switch, the `Host` check, the port and the log files.
2. DPAPI as a second kind of key protection, and the move of an unprotected key (ADR 0037).
3. The module `desktop`: window, start page, second start, icon and its menu.
4. `jpackage` and the Windows job of the release workflow; the winget step.
5. Update the README, the user guide (installing, updating, the data folder, the SmartScreen warning, lost key after moving) and `CONTRIBUTING.md`.
