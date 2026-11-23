# 011. Login throttling and session timeout

- Status: Accepted
- Date: 2026-11-23
- Related: [spec 001](../001-user-authentication/spec.md), [spec 012](../012-two-factor-authentication/spec.md)
- Changes when accepted: "Login throttling" and "Sessions" in spec 001

## Scope

Where the login throttling keeps its counter, how failed logins are logged, and how long a session lasts. A session without "Remember me" ends 30 minutes after the browser stopped sending requests, so a browser that is closed and opened again later, with its tabs restored, is logged out.

## Decisions

Decided by the owner on 2026-11-23:

1. The throttling counter is stored with the account instead of in memory. Every failed login counts, whatever username was entered, so the `429` does not reveal whether a username exists.
2. A session without "Remember me", and the session after setup, ends after 30 minutes without requests. With "Remember me" it keeps the 7 days.
3. Every request counts, including the ones the page sends on its own. The subscription list asks every 30 seconds, so an open page keeps its session alive. The timeout protects a browser that was closed, not an open page that was left alone.
4. No warning before a session ends.
5. The subscription list also asks while the tab is hidden, so only a closed browser or a computer in sleep mode ends the session. Browsers slow such requests down to about one per minute, which is enough.
6. Failed logins are logged, so the owner notices attacks and can block addresses with a tool of their choice.

## Details added by the AI assistant

Accepted by the owner with the spec on 2026-11-23:

1. A failed login is logged as `WARN` with the client address. When the request carries `X-Forwarded-For`, its value is logged too, escaped, because Wardroom does not know whether a reverse proxy in front of it can be trusted. The entered username and the password are never logged.
2. Attempts rejected during the waiting time are not logged one by one, so an attacker cannot fill the log. One line says when a waiting time starts and how long it is.
3. A successful login is logged as `INFO` with the client address, so the owner can see when and from where the account was used.
4. Sessions that exist when the update is installed keep the 7 days; only new logins get the 30 minutes.

## User stories

- As the owner, a browser where I did not choose "Remember me" is logged out when I open it again more than 30 minutes after closing it, even if it restores its tabs.
- As the owner, I am not logged out while Wardroom is open in my browser.
- As the owner, a restart of the container does not reset the protection against password guessing.
- As the owner, I see failed logins in the log, with the address they came from.

## Functional requirements

**Login throttling**

- The rules of spec 001 stay: after 5 consecutive failed logins, each further attempt waits 1 second, doubling up to 60 seconds; no permanent lockout; a successful login resets the counter.
- The counter and the end of the waiting time are stored with the account and survive a restart. A password reset (ADR 0017) removes them with the account row.
- Every failed login counts, whatever username was entered.
- A wrong second factor (spec 012) counts like a wrong password.

**Logging**

- A failed login: `WARN`, with the client address and the value of `X-Forwarded-For` if present.
- The start of a waiting time: `WARN`, with its length. Attempts during the waiting time are not logged.
- A successful login: `INFO`, with the client address.
- Never logged: the entered username, the password, the session ID.

**Session timeout**

- A session without "Remember me", and the session after setup, ends after 30 minutes without requests.
- A session with "Remember me" ends after 7 days without requests, as before.
- Every request with the session counts. The subscription list keeps asking every 30 seconds while the tab is hidden.
- An ended session answers `401`, as in spec 001.

## API contracts

No new endpoints. The responses of spec 001 are unchanged.

## Technical design

- At login and at setup, the inactivity limit of the new session is set to 30 minutes or 7 days, depending on "Remember me". Spring Session stores the limit with each session and removes expired sessions with its cleanup.
- The session cookie is unchanged: without "Remember me" it has no lifetime, with it the longest one the browser allows.
- `LoginThrottle` reads and writes the counter in the account row instead of in fields. Logins stay one after the other, as today.
- The subscription query sets `refetchIntervalInBackground`, so it keeps asking while the tab is hidden.
- Client addresses and `X-Forwarded-For` are untrusted text and are logged through `LogSafe`.

## Data model changes

- `wardroom_user`: new columns `failed_logins` (`INTEGER`, default 0) and `login_blocked_until` (`BIGINT`, epoch milliseconds, nullable).

## Error handling

| Situation | Behavior |
|---|---|
| Session ended after 30 minutes or 7 days | `401`; the frontend shows the login page |
| Throttled login after a restart | `429` with the remaining `Retry-After`, as before the restart |
| Throttled login before an account exists | The counter is kept in memory until the account exists; there is nothing to log in to yet |

## Security considerations

- The counter does not reveal whether a username exists.
- A restart no longer resets the throttling.
- `X-Forwarded-For` can be forged by anyone who reaches Wardroom directly. It is logged as what the request claims, next to the address the connection came from.

## Testing requirements

- The throttling state survives a restart and is removed with the account.
- Failed logins with an unknown username count.
- With a controlled clock: a session without "Remember me" ends after 30 minutes without requests, one with it after 7 days; a request within the limit starts it again.
- The session after setup has the 30-minute limit.
- Failed and successful logins are logged with the client address and `X-Forwarded-For`, never with username or password; attempts during a waiting time are not logged.
- Frontend: the subscription list asks while the tab is hidden.

## Implementation tasks

1. Migration for the throttling columns; `LoginThrottle` reads and writes them.
2. Set the inactivity limit of the session at login and setup.
3. Let the subscription list ask while the tab is hidden.
4. Log lines for failed logins, waiting times and successful logins.
5. Update spec 001 and the user guide once accepted; the user guide explains the log lines.
