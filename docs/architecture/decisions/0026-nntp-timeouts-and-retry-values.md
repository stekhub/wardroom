# 0026. NNTP timeouts and retry values

- Status: Accepted
- Date: 2026-11-18
- Extends: [ADR 0006](0006-resilience.md)

## Context

ADR 0006 decides that NNTP communication uses timeouts, retries with backoff and a circuit breaker per server, but gives no values. The specifications refer to them without values as well.

## Decision

| Value | Setting |
|---|---|
| Connect timeout | 10 seconds |
| Read timeout | 30 seconds |
| Retries | 3 attempts in total, waiting 2 and then 4 seconds |
| Circuit breaker | Opens after 5 consecutive failures, stays open for 5 minutes, then allows one trial request |
| Waiting for the interactive connection | At most 30 seconds, then the action fails with `502` ([spec 005](../../specs/005-article-synchronization/spec.md)) |

- The values are fixed in the application. They are not settings.
- Direct user actions (connection test, posting) are not retried, as before.

## Consequences

- A server that is down is contacted again after 5 minutes at the earliest.
- An interactive action never waits longer than about 30 seconds for a connection.
- The values can become settings later if servers turn out to need different ones.
