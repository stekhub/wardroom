# 0027. Committed OpenAPI specification

- Status: Accepted
- Date: 2026-11-18
- Extends: [ADR 0007](0007-frontend-stack.md)

## Context

ADR 0007 generates the frontend's API client from the backend's OpenAPI specification. The backend produces that specification only while it runs, but the frontend is built before the application is packaged.

## Decision

- The specification is committed as `openapi.json`. The frontend build generates the client from this file.
- A backend test compares the file with the specification the running application produces and fails when they differ. The same test can rewrite the file on request.
- The published image serves neither the specification endpoint nor Swagger UI. Both are available in development.

## Consequences

- The build needs no running backend.
- A changed API fails the build until `openapi.json` is updated, and the change is visible in review.
- The API is not self-documenting on a running instance; the committed file is the reference.
