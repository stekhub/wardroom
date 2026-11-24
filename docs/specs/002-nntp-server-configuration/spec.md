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
- As the owner, I enter a separate server for posting, because my provider takes posts only there.
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

**Posting server**

Some providers take posts only on another host than the one that is read from. The server can therefore have a separate posting server. It is part of the server, not a second server: it has no groups, no cache and no connection pool.

- The form has the checkbox "Use a separate server for posting". It is off by default. While it is off, posts go to the server itself and the fields below are not shown.
- When it is on, these fields are shown:

| Field | Rule | Default |
|---|---|---|
| Posting host | Host name or IP address, 1–253 characters | |
| Posting port | 1–65535 | 563 for `IMPLICIT`, 119 for `NONE` |
| Posting TLS mode | `IMPLICIT`, `NONE` | `IMPLICIT` |
| Use the same login as for reading | Checkbox | On |

- While "Use the same login as for reading" is on, the posting server gets the authentication, username and password of the server. If the server uses `USER_PASS`, the posting TLS mode must be `IMPLICIT`, so that the password is never sent unencrypted.
- When it is off, the posting server has fields of its own, with the rules of the server's fields: posting authentication (`NONE`, `USER_PASS`; `USER_PASS` requires the posting TLS mode `IMPLICIT`), posting username and posting password.
- The posting password is stored and handled like the password of the server: encrypted, write-only, and kept when the field is left empty on edit.
- Switching the first checkbox off deletes all stored values of the posting server. Switching "Use the same login as for reading" on, or the posting authentication to `NONE`, deletes the stored posting username and password.
- Changing the TLS mode of the posting server changes its port to that mode's default unless the user has edited the port.
- How a post uses the posting server is in [spec 009](../009-article-posting/spec.md).

**Password field**

- The password is write-only. The API never returns it, only whether one is stored.
- On edit the field is empty. Leaving it empty keeps the stored password.

**Connection test**

- The form has a "Test connection" button. It tests the values in the form without saving them; an empty password field uses the stored password.
- The test connects, validates the certificate as in ADR 0016, reads the greeting, sends `CAPABILITIES` and, for `USER_PASS`, authenticates, then sends `QUIT`.
- A server that does not support `CAPABILITIES` still passes.
- The result also reports whether the server offers posting ([spec 009](../009-article-posting/spec.md)).
- With a separate posting server, the server itself is tested first. If that works, the posting server is tested in the same way, and whether posting is offered is what the posting server says.
- A posting server that fails does not make the test fail, because reading works without it. The result is successful and names the failure of the posting server; the frontend shows "Posting: the posting server cannot be used" with the cause.
- The test is a direct user action: it bypasses the circuit breaker and is not retried (ADR 0006).

**Saving**

- Saving runs the same test and saves even if the test fails. The response contains the test result, and the frontend shows a warning on failure. This allows setting up a server that is temporarily unreachable.
- Saving new credentials clears `credentials_invalid`. Saving resets the server's circuit breaker.
- What the posting server answers does not change `credentials_invalid`, also when it uses the login of the server. After saving with a posting server that failed, the frontend warns that the posting server cannot be used.
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
  "credentialsInvalid": false,
  "postingServer": {
    "host": "post.example.com",
    "port": 563,
    "tlsMode": "IMPLICIT",
    "sameLogin": false,
    "authMethod": "USER_PASS",
    "username": "me",
    "passwordStored": true
  }
}
```

- Requests use the same fields without `id`, `passwordStored` and `credentialsInvalid`, plus an optional `password`.
- `postingServer` is `null` when posts go to the server itself. A request without it, or with `null`, removes the posting server.
- With `"sameLogin": true`, `authMethod` and `username` of the posting server are `null` and `passwordStored` is `false`. A request needs only `host`, `port`, `tlsMode` and `sameLogin` then.
- A validation failure of a posting server field names it with the prefix `postingServer.`, for example `postingServer.host`.

**Connection test result**

```json
{ "success": false, "failure": { "type": "TLS_CERTIFICATE", "message": "…" } }
```

Failure types: `UNREACHABLE` (host not found, connection refused), `TIMEOUT`, `TLS_CERTIFICATE`, `TLS_HANDSHAKE`, `AUTH_FAILED` (481), `SERVER_REFUSED` (400, 502), `PROTOCOL` (unexpected response). On success `failure` is `null`.

When the server works and its separate posting server does not, the result is:

```json
{ "success": true, "failure": null, "postingAllowed": null, "postingServerFailure": { "type": "UNREACHABLE", "message": "…" } }
```

`postingServerFailure` has the failure types above. It is `null` in every other case.

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
- The posting server is tested on a second dedicated connection, after the first one was closed.

**Limits for what a server sends**

- A line from a server is at most 1 MB long, without its line ending. This holds for the greeting, status lines, lists, overviews and articles.
- The answer to `CAPABILITIES` has at most 1,000 lines. It is the only answer that is kept as a whole; lists and overviews are processed line by line, and an article has its own size limit (spec 007).
- When a limit is exceeded, the connection is closed and the command fails with the failure type `PROTOCOL`. It is logged as a `PROTOCOL_ANOMALY` with the kind `response.too-long`, without anything of what the server sent.
- The limits are fixed values, not settings.

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
- The posting server adds six nullable columns to `nntp_server`; `posting_password_encrypted` uses the same format.
- New lifecycle rule for server removal in the data model.

## Error handling

| Situation | Behavior |
|---|---|
| Invalid field, `USER_PASS` with `NONE` TLS, missing username or password | `400` naming the field and rule |
| Invalid posting server field; the login of the server with a posting TLS mode `NONE` | `400` naming the field with the prefix `postingServer.` and the rule |
| The posting server fails in a connection test | `200`, successful, with `postingServerFailure` |
| The stored posting password cannot be decrypted | The connection test names it as `AUTH_FAILED` of the posting server; a post fails with the request to enter it again. `credentials_invalid` is not set |
| Creating a second server | `409` |
| Unknown server ID | `404` |
| Connection test fails | `200` with the failure type and message |
| Saving while the test fails | Saved; `connectionTest` contains the failure; the frontend warns |
| Credentials rejected later during synchronization (481) | `credentials_invalid` set (ADR 0006); the frontend shows a notice linking to the server settings |

## Security considerations

- Credentials are sent only over implicit TLS with a validated certificate (ADR 0012, ADR 0016). This also holds for the posting server, with its own login and with the login of the server.
- A new account does not inherit the posting server's own login either: its username and password are deleted and its authentication is set to `NONE` (ADR 0017).
- The password is never returned by the API, never logged, and never included in error messages or connection test results.
- Only ciphertext is stored in the database. By default the key file and database are both in `/config` and are backed up together (ADR 0009); owners can keep the key outside `/config` (ADR 0025). This protects a database file obtained alone, not a compromised host or running application (brief §5).
- The connection test lets the logged-in owner make the instance connect to any host and port. This is accepted for a single-owner, self-hosted instance.
- A server, or somebody in the middle of a connection without TLS, cannot fill the memory with a line that does not end or with an endless answer: what is read is limited.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- Field validation boundaries, the `USER_PASS`/`IMPLICIT` rule and the second-server `409`.
- The password is never present in any API response or log output.
- An empty password on edit keeps the stored password; switching authentication to `NONE` deletes the stored credentials.
- Connection test: success, server without `CAPABILITIES`, each failure type including an untrusted certificate and a host name mismatch.
- Saving with a failing test still saves and returns the failure.
- Limits: a line that does not end in the greeting, in a status line, in an article and in a list, and an answer to `CAPABILITIES` with too many lines, each fail with `PROTOCOL` and the anomaly `response.too-long`; a line as long as the limit is read.
- Posting server: saving and returning it with its own login and with the login of the server; the field rules, including the login of the server with a posting TLS mode `NONE`; the password is never in a response; an empty password on edit keeps the stored one; switching to the login of the server or removing the posting server deletes what was stored.
- Connection test with a posting server: both servers are tested; posting comes from the posting server; a failing posting server gives a successful result with its failure and does not set `credentials_invalid`; a failing server leaves the posting server untested.
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
7. Posting server: migration, rules, storage, connection test and the form fields.
7. Installation documentation: back up `/config` as a whole; what happens if the key file is lost. An advanced section of the user guide, outside the quick start, explains keeping the key outside `/config` with a Docker secret and how to move an existing key (ADR 0025, [ADR 0031](../../architecture/decisions/0031-user-guide.md)).
