# 0005. NNTP client library

- Status: Accepted
- Date: 2026-11-18

## Context

Wardroom needs NNTP support for capability discovery, group listing, overviews, article retrieval, authentication, TLS and posting (brief §3.1). Options in the Java ecosystem:

| Option | Assessment |
|---|---|
| Apache Commons Net `NNTPClient` | Maintained. Covers group selection, `XOVER`, article retrieval by Message-ID or number, `POST` with dot-stuffing, `AUTHINFO`, group listing. Implicit TLS possible through an `SSLSocketFactory`. No built-in `CAPABILITIES`, `OVER` or `STARTTLS`. |
| Other Java NNTP libraries | Unmaintained. |
| Own implementation | Full control; estimated 1,000–2,000 lines to build and maintain. |

## Decision

- Use **Apache Commons Net `NNTPClient`**.
- It is hidden behind Wardroom's own `NntpClient` interface. Domain and application code depend only on that interface, never on Commons Net types.
- Before the NNTP specification is finalized, a **timeboxed spike** verifies how Commons Net handles `CAPABILITIES`, `LIST NEWSGROUPS`, `LIST OVERVIEW.FMT`, `OVER` and `STARTTLS`, and documents any workarounds needed.
- Overview parsing follows the field order reported by `LIST OVERVIEW.FMT`.

The interface proposed in the brief (§6) is a starting point; its final shape (connection and session handling, group selection as session state) is defined in the NNTP specification.

## Consequences

- If Commons Net proves insufficient, an own implementation can replace it behind the same interface without changes to the domain.
- Gaps found in the spike are handled inside the adapter.
