# 0006. Resilience for NNTP communication

- Status: Accepted; values in [0026](0026-nntp-timeouts-and-retry-values.md)
- Date: 2026-11-18

## Context

Wardroom talks to several independent NNTP servers. Servers can be slow, unreachable or temporarily refuse connections, and providers limit the number of simultaneous connections per account. A failing server must not affect the others, and Wardroom must not hammer a server that is down or retry with credentials that are known to be wrong.

## Decision

**Circuit breaker: Resilience4j**

- Resilience4j is used **only** for circuit breaking.
- One circuit breaker **per NNTP server**, created programmatically from a registry keyed by server ID.

**Retry and concurrency: Spring Framework 7**

- Retries use Spring Framework 7's built-in resilience support (`@Retryable` or `RetryTemplate`), with backoff.
- `@ConcurrencyLimit` is available for capping concurrency of individual operations. Because it applies per method rather than per server, the **per-server connection limit** is enforced by the per-server connection handling in the NNTP adapter, using the maximum number of connections configured for that server.

**Timeouts**

Connect and read timeouts are set on the NNTP socket.

**Classification of NNTP responses**

| Response | Treatment |
|---|---|
| `411` no such group, `430` no such article | Normal result. Not a failure, no retry. |
| `481` authentication failed | Server is marked as having invalid credentials. No retry, until the user updates the configuration. |
| `400` service unavailable, `502` access denied, I/O errors, timeouts | Failure. Counts toward the circuit breaker; retried with backoff where the operation allows. |

## Consequences

- One unavailable server never blocks synchronization of others.
- Wrong credentials are not retried, which avoids account lockouts at providers.
- The adapter owns connection limits and response classification; callers receive domain-level results and errors.
