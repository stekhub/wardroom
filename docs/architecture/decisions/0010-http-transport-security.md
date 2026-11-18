# 0010. HTTP transport security

- Status: Accepted
- Date: 2026-11-18

## Context

The brief requires secure session cookies (§4). Many self-hosted installations run on a home network over plain HTTP, or behind a reverse proxy that terminates TLS. A cookie with the `Secure` flag is not sent over plain HTTP, so forcing it would break login on plain-HTTP installations.

What travels over the connection between browser and Wardroom: the Wardroom login password, the session cookie and article content. NNTP credentials never do; they stay encrypted on the server and are never returned by any API (brief §5).

## Decision

- Wardroom serves **plain HTTP**. It does not terminate TLS itself in the MVP.
- Session cookies are always `HttpOnly` and `SameSite`.
- The `Secure` flag is set automatically when the request arrived over HTTPS, including through a reverse proxy.
- Forwarded headers (`X-Forwarded-Proto` and related) are honored only when the request comes from a private-network address, so external clients cannot spoof them.
- The documentation states: plain HTTP is intended for trusted local networks. Access from the internet must go through a reverse proxy that terminates TLS. Port 8119 must not be exposed to the internet directly.

## Consequences

- Wardroom works out of the box on a local network.
- On plain HTTP, the Wardroom password and session cookie can be observed by others on the same network; the documentation makes this explicit.
- Installations behind a TLS reverse proxy get secure cookies without extra configuration.
