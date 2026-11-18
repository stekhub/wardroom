# 0029. Logging

- Status: Accepted
- Date: 2026-11-18

## Context

The specifications say in several places that something is logged or must never be logged, but there is no common rule for output, levels and content. Wardroom also talks to servers that do not always follow the RFCs. Such cases must be easy to find in the logs and complete enough to reproduce and fix them.

## Decision

**Output**

- Plain text to standard output only, so `docker logs` shows it. Wardroom writes no log file to `/config`; rotation is left to Docker.
- One line per event: UTC timestamp, level, component, message.
- The level is `INFO` by default. The environment variable `WARDROOM_LOG_LEVEL` changes it, for example to `DEBUG`.
- No HTTP access log and no log viewer in the UI. A failed request is logged with method and path.

**Levels**

| Level | Content |
|---|---|
| `ERROR` | Startup aborted (backup, migration, downgrade); unexpected errors behind a `500`, with stack trace |
| `WARN` | NNTP failure after all retries, circuit opened, credentials invalid, failed or throttled login, protocol anomalies, cache limit reached |
| `INFO` | Start with version, migrations and backups, each synchronization run per group with counts and duration, group list refresh, posting result, login, logout, password change, account reset, circuit closed again |
| `DEBUG` | NNTP commands and response codes, per-article decisions |

**Protocol anomalies**

A protocol anomaly is anything a server or an article sends that Wardroom cannot process as the RFCs describe: a malformed response line, an unexpected response code, a missing or malformed header, an unknown character set, a cycle in `References`.

- Every anomaly is logged at `WARN` on one line that starts with the fixed marker `PROTOCOL_ANOMALY`, followed by `key=value` fields.
- Fields: `kind`, a stable identifier of the case such as `overview.malformed-line`; `command`, the NNTP command that was running; `group`, `number` and `messageId` where known; `action`, what Wardroom did (for example `skipped`); `raw`, the offending line or header.
- `raw` is escaped, so that it stays on one line, and cut after 500 characters. It never contains article body text.
- The identifiers of `kind` are defined in one place in the code. A new case gets a new identifier.
- Within one run, the first 5 anomalies of a kind are logged in full. Further ones are counted, and the run ends with one summary line per kind with the total. At `DEBUG` every anomaly is logged in full.
- A fixed anomaly is added to the mock NNTP server as a regression case ([ADR 0008](0008-testing-strategy.md)).

**Never logged**

- Passwords, password hashes, session IDs, the encryption key and article bodies.
- The argument of `AUTHINFO PASS` is masked at every level.

**Untrusted text**

- Subjects, author names, Message-IDs and raw server lines have line breaks and control characters escaped before they are logged, so an article cannot forge log lines.

## Consequences

- Searching the log for `PROTOCOL_ANOMALY` gives every case where a server or article deviated, with enough data to reproduce it in a test.
- Logs can contain group names, subjects, author names and header lines. Owners should check a log before sharing it.
- A server that sends many faulty lines does not flood the log at the default level.
