# 0030. Test levels and build gate

- Status: Accepted
- Date: 2026-11-18
- Extends: [ADR 0008](0008-testing-strategy.md)

## Context

ADR 0008 names the test tools and the mock NNTP server. It does not say which kind of test covers what, when the tests must pass, or how coverage and logging are treated.

## Decision

- **Unit tests** cover logic that needs no database and no network, without starting Spring: threading, parsing, the author key, group icon mapping, character set decoding.
- **Integration tests** cover API, database and NNTP behavior with a real SQLite database and the mock NNTP server.
- **Build gate:** `mvnw verify` runs all backend and frontend tests. A step of the implementation plan moves to `In review` only when it passes.
- **Coverage:** a coverage report is generated. No percentage is enforced.
- **Logging tests** ([ADR 0029](0029-logging.md)): for each secret, a test checks that it does not appear in the log output; one test checks that untrusted text cannot add log lines; the tests for malformed server data check the `PROTOCOL_ANOMALY` line and its `kind`.
- **No browser end-to-end tests** in the MVP. Frontend behavior is tested with component tests against the mocked API.

## Consequences

- Most logic is tested fast and without the application context.
- Coverage numbers inform, but do not block the build.
- Errors that only appear in a real browser with the real backend are found by the manual check of each step.
