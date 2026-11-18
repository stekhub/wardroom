# 0007. Frontend stack

- Status: Accepted; dark mode is not part of the MVP ([UI concept](../../product/ui-concept.md#not-in-the-mvp)); client generation detailed in [0027](0027-committed-openapi-specification.md)
- Date: 2026-11-18
- Supersedes: brief §6 "Frontend: Angular"

## Context

The brief proposed Angular. The project owner prefers React and asked for a current, mainstream setup that is well documented and suits contributors with little React experience. Wardroom's frontend is a single-page application served by the backend; it needs routing, server-state handling, forms and an accessible component set.

## Decision

| Concern | Choice |
|---|---|
| Framework | React 19 (latest stable) |
| Language | TypeScript, strict mode |
| Build tool | Vite |
| Routing | React Router 7, single-page application mode |
| Server state | TanStack Query (caching, refetching, loading and error states). No global client-state library. |
| API client | Generated from the backend's OpenAPI specification (`openapi-typescript` + `openapi-fetch`). The backend publishes the specification via springdoc-openapi. |
| UI components and styling | Tailwind CSS v4 + shadcn/ui (accessible components whose source lives in the repository), including dark mode |
| Forms and validation | React Hook Form + Zod |
| Code quality | ESLint + Prettier |
| Package manager | pnpm |

**Build and serving**

- The Maven build drives the frontend build (`frontend-maven-plugin`), so `mvnw package` produces a single jar.
- Spring Boot serves the built frontend. Frontend and API share one origin.
- No server-side rendering.

## Consequences

- Frontend and backend API contracts cannot drift silently; a changed API changes the generated TypeScript types.
- Same-origin serving keeps session cookies and CSRF handling simple.
- Contributors need Node.js and pnpm only for frontend development; a full build works through Maven.
