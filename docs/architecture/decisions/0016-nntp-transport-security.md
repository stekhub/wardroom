# 0016. NNTP transport security

- Status: Accepted
- Date: 2026-11-18

## Context

Wardroom connects to the NNTP server with implicit TLS (port 563) or without TLS (port 119), and sends credentials only over TLS ([ADR 0012](0012-data-model.md), brief §5). Two further cases need a decision: servers with self-signed certificates, and servers that offer encryption only through `STARTTLS` on port 119 (RFC 4642).

`STARTTLS` upgrades a plaintext connection to TLS. An attacker in the middle can remove it from the server's capability list, so a client that then continues in plaintext would send credentials unencrypted. Apache Commons Net has no built-in `STARTTLS` for NNTP ([ADR 0005](0005-nntp-client-library.md)). Large providers offer implicit TLS on port 563.

## Decision

**Certificates**

- The server certificate is validated against the trust store of the Java runtime, including the host name.
- Self-signed certificates and custom certificate authorities are not supported in the MVP. A connection with an untrusted certificate fails with an error that names the cause.

**STARTTLS**

- Not supported in the MVP. TLS modes remain `IMPLICIT` and `NONE`.

## Consequences

- Servers with self-signed certificates cannot be used in the MVP.
- Servers that offer encryption only through `STARTTLS` can be used only without TLS, and therefore only without authentication.
- `STARTTLS` can be added later as a third TLS mode. It must then be strict: if the upgrade is not offered or fails, the connection is refused, never continued in plaintext. Capabilities are requested again after the upgrade, and authentication happens only afterwards.
- Support for self-signed certificates (pinning on first use or a custom CA) can be added later.
