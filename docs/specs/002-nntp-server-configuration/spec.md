# 002. NNTP server configuration

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.1, §4, §5, [ADR 0006](../../architecture/decisions/0006-resilience.md), [ADR 0009](../../architecture/decisions/0009-packaging-and-deployment.md), [ADR 0012](../../architecture/decisions/0012-data-model.md), [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0016](../../architecture/decisions/0016-nntp-transport-security.md)

## Scope

Adding, testing, editing and removing the NNTP server, and storing its credentials. This specification also covers credential storage (brief §5), so there is no separate credential storage specification.

Not covered: the posting identity ([spec 009](../009-article-posting/spec.md)), the group list fetch itself (newsgroup discovery specification).

## User stories

- As the owner, right after creating my account I enter my NNTP server, so that I can start reading.
- As the owner, I test the connection before saving, so that I know the settings work.
- As the owner, I change the server settings later, for example a new password or host, without losing my subscriptions and cache.
- As the owner, I remove the server, which removes all its data.
- As the owner, I can rely on my NNTP password never being shown again or written to logs.

## Functional requirements

**When the server is configured**

- While no server exists, the frontend shows the server form after login, including directly after account setup.
- Afterwards the server is edited on the settings page.
- The MVP allows exactly one server (ADR 0012).

**Fields**

| Field | Rule | Default |
|---|---|---|
| Name | 1–64 characters after trimming | |
| Host | Host name or IP address, 1–253 characters | |
| Port | 1–65535 | 563 for `IMPLICIT`, 119 for `NONE` |
| TLS mode | `IMPLICIT`, `NONE` | `IMPLICIT` |
| Authentication | `NONE`, `USER_PASS`; `USER_PASS` requires `IMPLICIT` | `USER_PASS` |
| Username | Required for `USER_PASS`, 1–255 characters | |
| Password | Required for `USER_PASS` when none is stored, 1–255 characters | |
| Max connections | 1–50 | 4 |

- In the frontend, changing the TLS mode changes the port to that mode's default unless the user has edited the port.
- Setting authentication to `NONE` deletes the stored username and password.

**Password field**

- The password is write-only. The API never returns it, only whether one is stored.
- On edit the field is empty. Leaving it empty keeps the stored password.

**Connection test**

- The form has a "Test connection" button. It tests the values in the form without saving them; an empty password field uses the stored password.
- The test connects, validates the certificate as in ADR 0016, reads the greeting, sends `CAPABILITIES` and, for `USER_PASS`, authenticates, then sends `QUIT`.
- A server that does not support `CAPABILITIES` still passes.
- The result also reports whether the server offers posting ([spec 009](../009-article-posting/spec.md)).
- The test is a direct user action: it bypasses the circuit breaker and is not retried (ADR 0006).

**Saving**

- Saving runs the same test and saves even if the test fails. The response contains the test result, and the frontend shows a warning on failure. This allows setting up a server that is temporarily unreachable.
- Saving new credentials clears `credentials_invalid`. Saving resets the server's circuit breaker.
- Saving a new server starts the group list fetch (ADR 0013). Editing does not; the group list is refreshed manually.

**Editing**

- Changing host, port, TLS mode or credentials keeps groups, subscriptions, cache and read state. Providers often serve the same article data under several host names.

**Removal**

- After confirmation in the frontend, removing the server deletes it and everything that belongs to it: groups, subscriptions, article locations, articles without remaining locations, and their read state ([data model](../../architecture/data-model.md#lifecycle-rules)).
- Afterwards the frontend shows the server form again.

## API contracts

All endpoints require a session and the CSRF token for state-changing requests ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457). Paths are plural so that several servers need no API change.

**Server representation**

```json
{
  "id": 1,
  "name": "My provider",
  "host": "news.example.com",
  "port": 563,
  "tlsMode": "IMPLICIT",
  "authMethod": "USER_PASS",
  "username": "me",
  "passwordStored": true,
  "maxConnections": 4,
  "credentialsInvalid": false
}
```

Requests use the same fields without `id`, `passwordStored` and `credentialsInvalid`, plus an optional `password`.

**Connection test result**

```json
{ "success": false, "failure": { "type": "TLS_CERTIFICATE", "message": "…" } }
```

Failure types: `UNREACHABLE` (host not found, connection refused), `TIMEOUT`, `TLS_CERTIFICATE`, `TLS_HANDSHAKE`, `AUTH_FAILED` (481), `SERVER_REFUSED` (400, 502), `PROTOCOL` (unexpected response). On success `failure` is `null`.

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/servers` | | `200` list of servers (empty or one) |
| `POST /api/servers` | Server | `201 { "server", "connectionTest" }`; `400` validation; `409` a server already exists |
| `PUT /api/servers/{id}` | Server | `200 { "server", "connectionTest" }`; `400`; `404` |
| `DELETE /api/servers/{id}` | | `204`; `404` |
| `POST /api/servers/connection-test` | Server, plus optional `id` whose stored password is used when `password` is empty | `200` connection test result; `400` validation |

A failed connection test is a result, not an error, and returns `200`.

## Technical design

**Connection test**

- Runs through the NNTP adapter's `NntpClient` with the adapter's normal timeouts, on a dedicated connection outside the per-server connection pool.
- Maps adapter errors to the failure types above. The message names the cause, for example the certificate problem, without echoing credentials.

**Credential encryption**

- Key: 32 random bytes from `SecureRandom`, stored in `/config/credentials.key` (ADR 0009). The file is created at application start if missing, owned by the application user, with permissions `0600`.
- Alternatively, the key is read from the file named by `WARDROOM_ENCRYPTION_KEY_FILE` ([ADR 0025](../../architecture/decisions/0025-external-encryption-key.md)); then no key is created in `/config`.
- Encryption: AES-256-GCM with a random 12-byte nonce per encryption.
- Stored format in `password_encrypted` (BLOB): one version byte (`1` = AES-256-GCM with the key file), the nonce, then ciphertext and 16-byte tag. The version byte allows other algorithms and key rotation later.
- Only the NNTP adapter decrypts, immediately before authenticating.

**Missing or unusable key**

| Situation at start | Behavior |
|---|---|
| Key file missing, no stored password | Generate the key, start normally |
| Key file missing, a password is stored | Generate a new key, start, set `credentials_invalid`, log a warning; the UI asks for the password again |
| Key file present but unreadable or not 32 bytes | Startup fails with an error naming the file; the file is never overwritten, because it may be recoverable |
| Decryption fails at runtime | Set `credentials_invalid`, log a warning; no retry |
| `WARDROOM_ENCRYPTION_KEY_FILE` set, file missing, unreadable or not 32 bytes | Startup fails with an error naming the file; no key is generated |
| `WARDROOM_ENCRYPTION_KEY_FILE` set and `/config/credentials.key` exists | The file in `/config` is ignored; a warning says it can be deleted |

## Data model changes

- None beyond the [data model](../../architecture/data-model.md). `password_encrypted` uses the format above.
- New lifecycle rule for server removal in the data model.

## Error handling

| Situation | Behavior |
|---|---|
| Invalid field, `USER_PASS` with `NONE` TLS, missing username or password | `400` naming the field and rule |
| Creating a second server | `409` |
| Unknown server ID | `404` |
| Connection test fails | `200` with the failure type and message |
| Saving while the test fails | Saved; `connectionTest` contains the failure; the frontend warns |
| Credentials rejected later during synchronization (481) | `credentials_invalid` set (ADR 0006); the frontend shows a notice linking to the server settings |

## Security considerations

- Credentials are sent only over implicit TLS with a validated certificate (ADR 0012, ADR 0016).
- The password is never returned by the API, never logged, and never included in error messages or connection test results.
- Only ciphertext is stored in the database. By default the key file and database are both in `/config` and are backed up together (ADR 0009); owners can keep the key outside `/config` (ADR 0025). This protects a database file obtained alone, not a compromised host or running application (brief §5).
- The connection test lets the logged-in owner make the instance connect to any host and port. This is accepted for a single-owner, self-hosted instance.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- Field validation boundaries, the `USER_PASS`/`IMPLICIT` rule and the second-server `409`.
- The password is never present in any API response or log output.
- An empty password on edit keeps the stored password; switching authentication to `NONE` deletes the stored credentials.
- Connection test: success, server without `CAPABILITIES`, each failure type including an untrusted certificate and a host name mismatch.
- Saving with a failing test still saves and returns the failure.
- Saving a new server starts the group list fetch; editing does not.
- Editing host or port keeps subscriptions and cache; removal deletes all server data.
- Encryption round trip; a different nonce for each encryption; the version byte is written.
- Missing key file with a stored password: startup succeeds and `credentials_invalid` is set. Key file of the wrong length: startup fails and the file is unchanged.
- With `WARDROOM_ENCRYPTION_KEY_FILE`: the key is read from that file, nothing is written to `/config`, a missing or wrongly sized file fails startup, and a leftover `/config/credentials.key` is ignored with a warning.

## Implementation tasks

1. Flyway migration for `nntp_server`.
2. Key file handling, including `WARDROOM_ENCRYPTION_KEY_FILE`, and the credential encryption service.
3. Connection test in the NNTP adapter, with failure mapping.
4. Server endpoints, validation and the removal cleanup.
5. Trigger of the group list fetch on creation; circuit breaker reset on save.
6. Frontend: server form (first run and settings page), connection test, save warning, removal confirmation, invalid credentials notice.
7. Installation documentation: back up `/config` as a whole; what happens if the key file is lost. An advanced section of the user guide, outside the quick start, explains keeping the key outside `/config` with a Docker secret and how to move an existing key (ADR 0025, [ADR 0031](../../architecture/decisions/0031-user-guide.md)).
