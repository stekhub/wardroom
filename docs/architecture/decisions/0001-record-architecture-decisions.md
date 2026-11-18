# 0001. Record architecture decisions

- Status: Accepted
- Date: 2026-11-18

## Context

Wardroom is developed specification-first. Decisions about technology and architecture need to be traceable, so contributors understand why things are the way they are, and so changes of direction are visible rather than silent.

## Decision

Significant decisions are recorded as Architecture Decision Records (ADRs) in `docs/architecture/decisions/`, using the template in this directory's README.

The [project brief](../../product/brief.md) is kept as the original context. Where an ADR changes direction set in the brief, the ADR states which part it supersedes; the brief itself is not edited to match.

## Consequences

- Every decision of lasting impact gets a short record before or alongside its implementation.
- Superseded decisions remain in the repository for history.
