# 0036. Windows app with a desktop mode

- Status: Accepted
- Date: 2026-11-26
- Extends: [ADR 0009](0009-packaging-and-deployment.md) (packaging), [ADR 0029](0029-logging.md) (output of the log)
- Related: [ADR 0037](0037-key-protection-with-dpapi.md), [spec 026](../../specs/026-windows-app/spec.md)

## Context

Wardroom is shipped as one Docker container (ADR 0009). The owner wants it on Windows without Docker, installed with winget, in a window of its own. Step 26 of the [implementation plan](../../implementation-plan.md).

The frontend talks to the backend over HTTP. In a window on the same computer, this means a web server on the loopback address. Nothing outside the computer can reach it, but two others can:

- Other Windows users on the same computer can connect to `127.0.0.1`.
- A website open in a browser can send requests to `127.0.0.1`. With DNS rebinding, its own domain name points to `127.0.0.1`, and the browser treats the answers as its own. Cookies of Wardroom are not sent along, so the session stays safe, but the parts without a login can be reached: the login form, and the setup wizard before the first run.

Ways without a port were considered: an embedded browser that answers the requests of the page inside the process, and an embedded browser with a message bridge instead of HTTP. Both need a window built and maintained by the project, make the installer much larger, and give up Edge in app mode, which is part of Windows. They are noted for a version in the Microsoft Store ([deferred](../../open-questions.md#deferred-beyond-the-mvp)).

## Decision

**One switch**

- The property `wardroom.windows-app=true` turns the desktop mode on. Only the launcher of the Windows installer sets it. Wardroom does not guess it from the operating system, so the jar or the image run by hand on Windows behaves as before.
- The switch turns on everything below in one place. With the switch set on another system than Windows, Wardroom does not start and the log says why.

**Local web server**

- The server listens on `127.0.0.1` only.
- Requests whose `Host` header is not `127.0.0.1:{port}` or `localhost:{port}` are refused with `421`. This closes DNS rebinding.
- The login, the session, the CSRF token and the login throttling stay as they are. They protect against other users of the computer.
- The setup wizard is open until the account exists, as in the image. With loopback only and the `Host` check, only another user of the same computer could reach it in the first minute after the installation. This is accepted.

**Port**

- At the first start, Wardroom picks a free port and stores it in the data folder. Later starts use the same port again when it is free.
- The same port keeps the address of the window the same, so what the browser stores for it (the theme, the keyboard setting, the session of "Remember me") is kept. Only when the port is taken does Wardroom pick a new one, and these are lost once.

**Window**

- The window is Edge in app mode, without an address bar or tabs, with a profile of its own in the data folder. Extensions and the browsing history of the owner's own Edge do not reach it. If Edge cannot be found, the default browser opens.

**Log**

- In the desktop mode, nobody sees the standard output. The log is also written to files in the data folder, `logs\wardroom.log`, rotated daily and kept for 7 days. Format, levels and the rules of ADR 0029 stay the same.

**Packaging and distribution**

- An MSI built with `jpackage`, with the Java runtime included, installed per user without an administrator prompt, with a fixed upgrade ID.
- winget is the main way to install. The MSI is also attached to each GitHub release for a direct download.
- The MSI is not signed at first. winget checks the file against the hash in its manifest, and its review scans the package. A direct download shows the SmartScreen warning, which the user guide explains.
- Signing can come later, for the direct download. The first choice is the SignPath Foundation, which signs open-source projects for free from GitHub Actions after it accepted the project. Then both `Wardroom.exe` and the MSI are signed. This needs a new decision when it is done.

## Consequences

- Wardroom is no longer only one Docker container. The release workflow gets a Windows machine and a winget step.
- The desktop mode is a small layer on the same application. The image is not changed by it.
- Other users of the same computer can reach the login page. They need the password, as anyone who reaches the image over the network.
- Moving the data folder to another computer or user loses the key (ADR 0037).
- A one-time token in the address of the window, which would log the owner in without a password, was considered. It is not part of this decision and can be added later.
