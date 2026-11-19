# Wardroom

Wardroom is a free, open-source (AGPL), self-hosted NNTP Usenet client with a modern web UI. One Docker container, one owner account, one NNTP server in the MVP.

## Where things are

| Path | Content |
|---|---|
| `docs/product/brief.md` | Original project brief; parts are superseded by ADRs, which say so |
| `docs/product/ui-concept.md` | Layout, visual design, wizard, settings page, shared interaction rules |
| `docs/product/group-icons.md` | Group icon rules and keyword table |
| `docs/architecture/decisions/` | ADRs; the index in its `README.md` shows which are superseded |
| `docs/architecture/data-model.md` | Tables, columns and lifecycle rules |
| `docs/specs/NNN-*/spec.md` | Feature specifications, all Accepted |
| `docs/user-guide.md` | Guide for people who run Wardroom; the README links to it (ADR 0031) |
| `docs/ai-guidelines.md` | How the AI assistant is used and which rules keep humans in control; binding for this work |
| `CONTRIBUTING.md` | Contribution guidelines for people |
| `docs/open-questions.md` | Undecided items and the list deferred beyond the MVP |
| `docs/implementation-plan.md` | Implementation steps, their order and status; **read it first when implementing** |

## Source of truth

- The docs are the specification. Implement what they say; do not add features, settings or endpoints they do not describe.
- Precedence: a newer ADR over an older one it supersedes; ADRs and specs over the brief.
- If a spec is ambiguous, contradicts another document or turns out to be wrong during implementation: stop, describe the problem and propose a fix. Do not decide silently. Accepted ADRs are never rewritten; a changed decision gets a new ADR.
- Anything in "Deferred beyond the MVP" or a spec's "not part of the MVP" stays out.

## Working with the owner

- Discussions: propose numbered points, the owner approves or changes them, then document. Mark your own additions as such so they can be vetoed.
- Keep the MVP simple; further features are added on demand.
- Commit and push only when the owner asks, each time. Work on a branch, never directly on `main`.
- Branch names: a type prefix, a slash and a short lowercase name with hyphens. `feature/step-NN-short-title` for implementation steps; `fix/`, `docs/` and `chore/` for other work.
- Write user-facing docs in English, in short, plain sentences.
- Do not mention other applications or application stacks in the docs.
- Keep personal motives of the owner out of the docs.

## Implementation rules

**Backend** (ADR 0002, 0003, 0005, 0006)

- Java 25, Spring Boot 4, Maven Wrapper, Spring Modulith; module boundaries are verified in a test.
- SQLite with Flyway (plain SQL migrations) and jOOQ generated from the migrated schema; generated code is not committed. Migration rules in ADR 0004; before the first release, migrations may still be reset.
- Configuration via `@ConfigurationProperties` records with Bean Validation. Records for value objects and DTOs. Lombok only for `@RequiredArgsConstructor`, `@Slf4j`, `@Builder`; no `@Data` on domain classes.
- NNTP only through Wardroom's own `NntpClient` adapter around Apache Commons Net; circuit breaker with Resilience4j and retries with Spring Framework, as in ADR 0006 and 0026.
- API errors as Problem Details (RFC 9457).

**Frontend** (ADR 0007, UI concept)

- React 19, TypeScript strict, Vite, React Router 7, TanStack Query, shadcn/ui with Tailwind v4, Lucide icons, React Hook Form with Zod, pnpm.
- The API client is generated from the committed `openapi.json` (ADR 0027); never hand-write API types.
- Follow the visual design rules in the UI concept: design tokens, Inter bundled, skeletons, tooltips on icon buttons, WCAG 2.2 AA. Light theme only in the MVP.

**Tests** (ADR 0008, 0030)

- Every spec's "Testing requirements" are implemented with the step.
- Integration tests use a real SQLite database and the mock NNTP server from the test sources. Never a real NNTP server.
- NNTP fixtures keep exact CRLF line endings.
- Frontend: Vitest, React Testing Library, MSW.
- Unit tests without Spring for pure logic; `mvnw verify` must pass before a step goes to "In review".

**Logging** (ADR 0029)

- Standard output only, `INFO` by default.
- Anything a server or article sends that violates the protocol is logged as a `PROTOCOL_ANOMALY` line with a stable `kind`; never skip such data silently.
- Escape untrusted text before logging it.

**Security**

- Never log, return or include in errors passwords, password hashes, session IDs or the encryption key. Never log article bodies.
- Article content, subjects and names are untrusted and rendered as text, never as HTML.

## Commands

Run from the repository root with JDK 25. Maven, Node.js and pnpm are downloaded by the build; on Windows use `mvnw.cmd`.

| Command | Purpose |
|---|---|
| `./mvnw verify` | Full build: backend, frontend, all tests, coverage report in `target/site/jacoco`. The build gate (ADR 0030) |
| `./mvnw test -Dtest=ClassName` | One backend test class |
| `./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"` | Start on port 8119 with the data directory `./config`; Swagger UI at `/swagger-ui.html` |
| `./mvnw test -Dtest=OpenApiSpecificationTest "-Dopenapi.update=true"` | Rewrite `openapi.json` after an intended API change (ADR 0027) |
| `pnpm dev` in `frontend/` | Frontend with hot reload; forwards `/api` to the backend on port 8119 |
| `pnpm check` in `frontend/` | Lint, format check and frontend tests |
| `pnpm format` in `frontend/` | Format the frontend sources |
| `docker build -t ghcr.io/stekhub/wardroom:latest .` then `docker compose up` | Build the image locally and start it with the reference Compose file |

- The quotes around `-D` arguments that contain a dot are needed in PowerShell, which otherwise splits the argument at the dot.
- `WARDROOM_LOG_LEVEL=DEBUG` shows NNTP commands and per-article decisions (ADR 0029).
- Shared technical code (data directory, database startup, logging helpers, API error handling) is in the `platform` module. Log untrusted text only through `LogSafe`, and protocol anomalies only through `ProtocolAnomalyLog`.
- Integration tests are annotated with `@IntegrationTest`.
- Other modules talk NNTP only through `NntpConnections` (pooled connections with connection limit, circuit breaker and retries) and `NntpConnector` (one dedicated connection, for the connection test). New NNTP commands are added to `NntpClient`.
- The mock NNTP server is `MockNntpServer` in `src/test/java/.../nntp/mock`; answers and faults are scripted with `MockReply`. Fixtures are in `src/test/resources/nntp` and keep their bytes.
- shadcn/ui components are added with `pnpm dlx shadcn@latest add <name>` in `frontend/`.
