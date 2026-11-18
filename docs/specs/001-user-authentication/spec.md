# 001. User authentication

- Status: Accepted
- Date: 2026-11-18
- Related: brief §4, [ADR 0010](../../architecture/decisions/0010-http-transport-security.md), [ADR 0015](../../architecture/decisions/0015-account-setup-and-recovery.md), [ADR 0017](../../architecture/decisions/0017-password-reset.md)

## Scope

Creating the single Wardroom account at first start, logging in and out, sessions, changing the password, and resetting a forgotten password. NNTP credentials are covered by the server configuration specification.

## User stories

- As the owner, after the first start I create my account in the browser, so that only I can use the instance.
- As the owner, I log in with username and password and stay logged in across container restarts.
- As the owner, I log out, so that the browser no longer has access.
- As the owner, I change my password on the settings page.
- As the owner, if I forget my password, I can reset it with access to the database file.

## Functional requirements

**Setup**

- While no account exists, the frontend shows the setup page and no other page.
- Setup asks for a username and a password (entered twice in the frontend).
- Creating the account runs the cleanup from ADR 0017, stores the account and logs the owner in.
- Once an account exists, setup is no longer available.

**Credentials rules**

- Username: 1–64 characters after trimming surrounding whitespace, stored as entered, compared exactly.
- Password: 12–128 characters, any characters, no composition rules. Not trimmed.

**Login and logout**

- Login with username and password. A failure gives one generic message, without saying which of the two was wrong.
- Logout ends the current session.

**Sessions**

- Sessions are stored in SQLite and survive restarts. After an update, a session that can no longer be read counts as logged out, and the owner logs in again.
- A session ends after 30 days of inactivity. There is no "remember me" option.

**Login throttling**

- After 5 consecutive failed logins, each further attempt must wait: 1 second, doubling with every further failure, up to 60 seconds.
- Attempts during the waiting time are rejected without checking the password.
- A successful login resets the counter. The counter is held in memory and resets on restart.
- There is no permanent lockout.

**Password change**

- On the settings page; requires the current password and the new password (entered twice in the frontend).
- Ends all other sessions; the current session stays logged in.

**Password reset**

- As in ADR 0017: delete the account row while the container is stopped; on the next start setup is available again.

## API contracts

All endpoints are under `/api/auth`. Request and response bodies are JSON. Errors use Problem Details (RFC 9457). All endpoints that change state require the CSRF token.

| Method and path | Access | Request | Responses |
|---|---|---|---|
| `GET /api/auth/setup` | Public | | `200` while setup is available; `404` afterwards |
| `POST /api/auth/setup` | Public | `{ "username", "password" }` | `201` account created and logged in; `400` validation; `404` an account already exists |
| `POST /api/auth/login` | Public | `{ "username", "password" }` | `204` logged in; `401` invalid credentials; `429` throttled, with `Retry-After` |
| `POST /api/auth/logout` | Logged in | | `204` |
| `GET /api/auth/me` | Logged in | | `200 { "username" }`; `401` |
| `PUT /api/auth/password` | Logged in | `{ "currentPassword", "newPassword" }` | `204`; `400` validation or wrong current password |

**Access rules for everything else**

- All other `/api/**` endpoints require a session and answer `401` without one. They never redirect.
- The health endpoint and the frontend's static files are public.

## Technical design

- Spring Security with a session cookie; no tokens in local storage.
- Passwords are hashed with Argon2id using Spring Security's `Argon2PasswordEncoder` defaults.
- Sessions use Spring Session JDBC on the SQLite database. Its tables are created by a Flyway migration, not by Spring Session. Spring Session does not officially support SQLite; if it does not work, a session store of Wardroom's own with the same behavior replaces it.
- A stored session that cannot be read is deleted and treated as logged out.
- The session ID is changed at login and at setup (protection against session fixation).
- Session cookie: `HttpOnly`, `SameSite=Lax`, `Secure` as in ADR 0010.
- CSRF: Spring Security's cookie-based CSRF token. The token is in a cookie readable by the frontend, which sends it back in a request header on every state-changing request.
- On each request, a session whose account no longer exists is treated as logged out.
- Setup runs in one transaction and checks inside it that no account exists, so two simultaneous setup requests cannot both succeed.

## Data model changes

- `wardroom_user` as in the [data model](../../architecture/data-model.md); no new columns.
- New tables for Spring Session JDBC (session and session attributes).

## Error handling

| Situation | Behavior |
|---|---|
| Invalid username or password format at setup or password change | `400` with the field and rule that failed |
| Wrong credentials at login | `401`, generic message |
| Throttled login | `429` with `Retry-After` in seconds; the frontend shows the waiting time |
| Setup after an account exists, including the losing side of a simultaneous setup | `404` |
| Expired or invalid session | `401`; the frontend shows the login page |
| Missing or invalid CSRF token | `403` |

## Security considerations

- Passwords and password hashes never appear in logs or API responses.
- Login always runs a password hash, even for an unknown username, so response times do not reveal whether the username exists.
- The setup window after first start and after a reset is accepted (ADR 0015). The installation documentation tells users to complete setup immediately.
- Throttling is per instance, not per client address, because there is one account and client addresses behind a reverse proxy are not reliable.

## Testing requirements

Integration tests against a real SQLite database:

- Setup is available only without an account; afterwards `GET` and `POST /api/auth/setup` return `404`.
- Two simultaneous setup requests: exactly one succeeds.
- Setup after the account row was deleted: sessions and NNTP credentials are removed, authentication is `NONE`; connection settings, subscriptions, cache and read state are unchanged.
- Login succeeds with correct credentials and fails with a generic `401` otherwise.
- Throttling starts after the fifth failure, doubles up to 60 seconds and resets after a successful login.
- A session survives an application restart and ends after the inactivity timeout; an unreadable stored session is treated as logged out.
- Logout ends the session.
- Password change requires the current password and ends other sessions but not the current one.
- Protected endpoints return `401` without a session; state-changing requests without the CSRF token return `403`.
- Validation boundaries for username and password lengths.

## Implementation tasks

1. Flyway migration for `wardroom_user` and the Spring Session tables.
2. Security configuration: session cookie, CSRF, access rules, `401` instead of redirects.
3. Setup endpoints, including the account-creation cleanup.
4. Login and logout endpoints with throttling.
5. `me` and password change endpoints.
6. Frontend: setup page, login page, logout, password change on the settings page, routing based on setup and session state.
7. Installation documentation: complete setup immediately; how to reset a forgotten password; an update may require logging in again.
