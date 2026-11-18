# 0008. Testing strategy

- Status: Accepted; test levels and build gate in [0030](0030-test-levels-and-build-gate.md)
- Date: 2026-11-18

## Context

Wardroom's riskiest code talks to external NNTP servers, parses untrusted article content and migrates user databases. Tests must cover these areas reliably, without depending on external services.

## Decision

**Backend**

- JUnit with Spring Boot's test support (including AssertJ and Mockito).
- Integration tests run the application against a real SQLite database.
- A **mock NNTP server**, written in the test sources: a scriptable socket server that replays NNTP responses as specified in RFC 3977 and can inject faults (dropped connections, authentication failures, slow responses). It is used to test the NNTP adapter, synchronization and resilience behavior.
- **No real NNTP server** is used in tests, neither external nor containerized.
- Spring Modulith's module verification test runs as part of the suite ([ADR 0002](0002-backend-platform.md)).
- Database upgrade tests as defined in [ADR 0004](0004-database-upgrades.md).

**Frontend**

- Vitest + React Testing Library for components.
- MSW (Mock Service Worker) to simulate the backend API.

## Consequences

- The test suite runs offline and deterministically.
- The mock server's protocol behavior must follow the RFCs closely; mismatches with real servers are found only through manual testing and user reports, and are then added to the mock as regression cases.
- NNTP test fixtures contain exact CRLF line endings; the repository must preserve them byte-for-byte when such fixtures are added.
