# Implementation Plan

The order in which the MVP is built, and the status of each step. Each step is one branch and one pull request, small enough to finish and review in one sitting. The specifications define *what* is built; this plan only defines the order and the scope of each step.

## Status

| # | Step | Specs and ADRs | Status |
|---|---|---|---|
| 1 | Project skeleton | ADR 0002, 0003, 0004, 0007, 0008, 0009, 0024, 0027, 0028, 0029, 0030 | Done |
| 2 | NNTP foundation | ADR 0005, 0006, 0016, 0026; open question 1 | Done |
| 3 | Authentication and application shell | Spec 001; UI concept | In review |
| 4 | Server configuration and first-run wizard | Spec 002; ADR 0025; UI concept | Not started |
| 5 | Newsgroup discovery | Spec 003; group icons | Not started |
| 6 | Subscriptions | Spec 004 | Not started |
| 7 | Synchronization core | Spec 005 | Not started |
| 8 | Load older, load more, jump to date | Spec 005; ADR 0021, 0022 | Not started |
| 9 | Threading | Spec 006 | Not started |
| 10 | Article reading | Spec 007 | Not started |
| 11 | Discussion view | Spec 008 | Not started |
| 12 | Ignoring | Spec 010 | Not started |
| 13 | Posting | Spec 009 | Not started |
| 14 | Cache size limit | ADR 0023; spec 005 | Not started |
| 15 | Polish and documentation | UI concept; spec 001, 002 | Not started |
| 16 | Release pipeline | ADR 0009, 0024 | Not started; discuss first |

Status values: `Not started`, `In progress`, `In review`, `Done`.

Wardroom can first be used for reading after step 11.

## Steps

### 1. Project skeleton

- Maven project with the Maven Wrapper, Maven Enforcer (Java 25), Spring Boot 4, Spring Modulith with the module verification test.
- Maven group `io.github.stekhub`, base package `io.github.stekhub.wardroom`, version `0.1.0-SNAPSHOT`. Modules: auth, server, nntp, groups, sync, articles, posting, ignoring, settings.
- The data directory is a property that defaults to `/config`; development uses `./config`.
- SQLite, Flyway and jOOQ code generation from the migrated schema; WAL mode and `busy_timeout` (ADR 0003).
- Startup sequence of ADR 0004: Flyway first, backup with `VACUUM INTO` before pending migrations, downgrade protection, the 2 newest backups kept (ADR 0028).
- Health endpoint, springdoc-openapi, Problem Details; `openapi.json` committed with its test (ADR 0027).
- Logging setup: format, `WARDROOM_LOG_LEVEL`, escaping of untrusted text, the `PROTOCOL_ANOMALY` line (ADR 0029). Coverage report (ADR 0030).
- Frontend: Vite, React 19, TypeScript strict, React Router, TanStack Query, generated API client, Tailwind v4 and shadcn/ui with the design tokens, Inter bundled, Lucide, ESLint, Prettier, Vitest and MSW; built by Maven into the single jar.
- `.gitattributes` that keeps NNTP fixtures byte-for-byte (CRLF).
- Dockerfile (multi-stage, amd64 and arm64) with the `PUID`/`PGID` entrypoint, `HEALTHCHECK` and `compose.yaml` as in ADR 0009. Built locally only; publishing is step 16.
- CI workflow: build and tests on every push and pull request.
- Fill in the "Commands" section of `CLAUDE.md`.

**Done when:** `mvnw verify` builds the jar with the frontend and passes; the container starts with a `/config` volume and is healthy; an empty page is served on port 8119.

### 2. NNTP foundation

- Library spike: verify Apache Commons Net for `CAPABILITIES`, streaming `LIST ACTIVE`, `LIST NEWSGROUPS`, `LIST OVERVIEW.FMT`, `OVER` and `POST` (open question 1). Record the result; if something is missing, propose how to handle it before continuing. The spike may include a manual, read-only check against a public server that needs no login; automated tests never use a real server.
- Mock NNTP server in the test sources, scriptable, with fault injection (ADR 0008).
- `NntpClient` adapter: connect with implicit TLS or none (ADR 0016), authentication, `CAPABILITIES`, timeouts; protocol anomalies logged as in ADR 0029, with `AUTHINFO PASS` masked.
- Per-server connection pool with the connection reserved for interactive actions (spec 005), Resilience4j circuit breaker and Spring Framework retry with the values of ADR 0026 (ADR 0006).

**Done when:** the adapter connects, authenticates and reads capabilities against the mock server, including the failure cases.

### 3. Authentication and application shell

- Spec 001 completely, except the installation documentation (step 15).
- Application shell from the UI concept: sidebar frame, routing, settings page frame with section navigation, toasts, skeletons.
- First-run wizard step 1 (account).
- Logo: the files in `assets/logo/`, a light and a dark variant. The MVP uses the light variant. The favicon is derived from it as a square image.

### 4. Server configuration and first-run wizard

- Spec 002 completely, including `WARDROOM_ENCRYPTION_KEY_FILE` (ADR 0025), except the documentation (step 15).
- Wizard step 2 (server), resuming after a closed browser; settings section "Server".
- The group list fetch trigger is wired in step 5.

### 5. Newsgroup discovery

- Spec 003 completely, including the group icon function and its tests, and the scheduled group list refresh (ADR 0020). Icon names that current Lucide has renamed are mapped, and the table in Group Icons is updated.

### 6. Subscriptions

- Spec 004 completely: subscribe toggle, sidebar entries, unsubscribe cleanup, mark all as read. Unread counts stay 0 until articles exist (step 7).

### 7. Synchronization core

- Spec 005: schedule, first and incremental synchronization in chunks, synchronized ranges, expiry, renumbering, manual refresh, settings section "Synchronization" (without the cache size, step 14).

### 8. Load older, load more, jump to date

- Spec 005 and ADR 0021, 0022: extending ranges, date search, articles arriving read.

### 9. Threading

- Spec 006 completely, including the recomputation hooks of steps 7 and 8.

### 10. Article reading

- Spec 007 completely: content fetch, Mime4j extraction, read marking, Content Security Policy, author icons, excerpts.

### 11. Discussion view

- Spec 008 completely: thread list, sorting, paging, date view, thread page, mark thread as read.

### 12. Ignoring

- Spec 010 completely: rules, author key, filters in lists and counts, settings section "Ignored".

### 13. Posting

- Spec 009 completely, including posting availability in the connection test.

### 14. Cache size limit

- ADR 0023 and the cache parts of spec 005: measurement, cleanup job, setting with current use, warning.

### 15. Polish and documentation

- Quick switcher (`Ctrl+K` / `⌘K`), settings section "About", accessibility pass against WCAG 2.2 AA.
- README: introduction, quick start with the reference Compose file, first-run wizard, link list to the user guide (ADR 0031).
- User guide (`docs/user-guide.md`): backups, updates, password reset (spec 001), lost key file (spec 002), and an advanced section on keeping the encryption key outside `/config` (ADR 0025, 0031).
- The README and the user guide were written early, on 2026-11-19. In this step: check both against the finished application and replace the screenshot placeholder in the README with a real screenshot.

### 16. Release pipeline

- To be discussed with the owner before implementation: release workflow, publishing to `ghcr.io/stekhub/wardroom` for amd64 and arm64, tags, GitHub setup.
- When the repository is made public: turn on private vulnerability reporting in the repository settings (Settings, Security), because `CONTRIBUTING.md` points to it. GitHub offers it only for public repositories. Remind the owner.
- Until the first release, the README needs a line saying that Wardroom is in development and no image is published yet.

## Working on a step

1. Read this plan, `CLAUDE.md`, the step's specs and the ADRs and documents they reference.
2. Create a branch `feature/step-NN-short-title` from `main`. Set the step to `In progress`.
3. Implement the step's scope and its specs' testing requirements. Keep to the step; note anything that belongs elsewhere under "Notes" below.
4. Run the full build and tests (`mvnw verify` must pass, ADR 0030); start the application and check the step's feature in the browser.
5. Set the step to `In review` and summarize for the owner: what was built, how to try it, deviations and open points. The owner reviews; commits and pull requests are made when the owner asks.
6. After merging, set the step to `Done`.

**Stopping mid-step** (for example when a usage limit is near): record under "Notes" what is finished, what is not, and the next action, so a new session can continue from the repository alone.

## Notes

Progress notes, deviations and findings, newest first.

**Step 3, authentication and application shell (2026-11-19)**

What was built:

- Backend: the six endpoints of spec 001 below `/api/auth`, the access rules, sessions in the database, login throttling, and the account creation with its cleanup. Spring Session JDBC works with SQLite; no session store of Wardroom's own was needed.
- Frontend: login page, wizard step 1 (account), the shell with the sidebar, the settings page with its section navigation, the "Account" section with the password change, log out, toasts and skeletons. The logo and the favicon are in place.
- A module that stores sensitive data implements `AccountCreationCleanup` of the `auth` module. Its cleanup runs in the transaction that creates the account (ADR 0017).

Decisions made during the step, open to a veto:

- Username: surrounding whitespace is removed at setup and at login. Apart from that, the username is stored as entered and compared exactly, including upper and lower case. Spec 001 says "stored as entered" and "after trimming"; this is how both were read.
- Lengths of username and password are counted in characters, not in bytes. An emoji is one character.
- The session cookie is kept by the browser for 400 days, the longest time browsers allow. Spec 001 does not say how long the cookie lives. Without a lifetime, closing the browser would log out. The 30 days of inactivity are enforced by the server.
- A login always gets a new session; a session the browser had before is ended.
- Logins are checked one after the other, so parallel attempts cannot get around the throttling.
- A password that is too long to be valid is answered like a wrong password (`401`), not with `400`.
- A wrong current password at the password change is not throttled. Spec 001 only throttles the login.
- Validation errors of all endpoints have one shape: `detail` is the first failed rule, and `errors` lists every failed field with its rule.
- Files below `/assets` may be kept by the browser for a year, because their names change with their content. Everything else is checked with the server on every page load.
- The settings navigation shows all six sections of the UI concept. A section whose feature is not built yet says so.
- `/` leads to the discover view, which has only its heading so far.
- After wizard step 1, the application is shown directly. Step 4 adds wizard step 2 and the rule that the application needs a server.
- The sidebar collapses to a narrow column of icons and remembers this in a cookie. In a narrow window it opens over the page.

Findings:

- jOOQ maps `INTEGER` columns to 32-bit numbers. Columns for times in milliseconds or for article numbers must be declared `BIGINT` in the migrations. The session tables do this.
- Spring Boot would create a second user with a generated password and print that password in the log. This is switched off.
- The attributes of the session cookie (`HttpOnly`, `SameSite`, `Secure`) are set by the web server, so they are tested with a real server in `SessionRestartTest`, not with the usual integration tests.
- The logo files are copied into `frontend/src/assets`, because the image build does not include `assets/`. The favicon in `frontend/public` is derived from the logo.
- The build no longer prints `[ERROR]` lines when nothing failed: pnpm is started with `--silent`, which removes the line that repeats its command. A real failure of the frontend checks still shows its messages and ends with `BUILD FAILURE`.
- New dependencies: Spring Security, Spring Session JDBC, Bouncy Castle (needed for Argon2), and in the frontend the toast library of shadcn/ui.

Left for later steps:

- The test "setup after the account row was deleted" covers the sessions and that cleanups run. The part about the NNTP credentials is tested in step 4, when they exist.
- Installation documentation of spec 001 (step 15): complete setup immediately; an update may require logging in again.

Not verified:

- That forwarded headers from an address outside a private network are ignored. This is the web server's default behavior and was not tested; the test only shows that a local address is trusted.
- The image build. The application was tried in the browser with the jar built by Maven.

**Step 2, NNTP foundation (2026-11-19)**

Library spike (open question 1). Apache Commons Net 3.13.0 is sufficient; nothing has to be replaced. The findings are kept as tests in `CommonsNetSpikeTest`.

| Command | Result |
|---|---|
| `CAPABILITIES` | No method. Works as a generic command; the adapter reads the multi-line answer itself |
| `LIST ACTIVE` | Method exists and streams: groups are handed out while the list is still arriving, with one line of look-ahead |
| `LIST NEWSGROUPS` | No method. Works as a generic command |
| `LIST OVERVIEW.FMT` | Method exists |
| `OVER` | No method; the library's overview method sends the older `XOVER`. `OVER` works as a generic command |
| `POST` | Method exists, with dot-stuffing |
| Character data | The library reads ISO-8859-1, so every byte survives and can be decoded later with the right character set |

- No check against a real server was made. Everything was verified against the mock server only.

What was built:

- Other modules use two interfaces of the `nntp` module. `NntpConnections` runs work on pooled connections, with the connection limit, the circuit breaker and retries. `NntpConnector` opens one dedicated connection without them, for the connection test. Both hand out an `NntpClient`, which so far offers the capabilities and the posting flag of the greeting. Later steps add the commands.
- The mock NNTP server is `MockNntpServer` in the test sources, with `MockReply` for scripted answers and faults. Fixtures are in `src/test/resources/nntp`.

Decisions made during the step, open to a veto:

- The capabilities are requested again after the login, because a server may offer more to an authenticated client (RFC 3977).
- The circuit breaker counts every attempt, not every request. With 3 attempts per request, the circuit opens during the second failing request. An open circuit ends the retries of the running request.
- A certificate failure counts toward the circuit breaker but is not retried. A rejected login and a protocol violation are neither retried nor counted.
- Response `482` at login is treated like `481`, because older servers use it for rejected credentials.
- An unused connection is kept for 60 seconds and then replaced. A connection on which an action failed is closed. Changed server settings always get a new connection.
- The adapter refuses settings with credentials but without TLS, in addition to the validation of spec 002.
- Background work waits without a time limit for a free connection; only interactive actions have the 30 second limit.
- The adapter has three failure types that spec 002 does not list: `CONNECTION_LOST`, `CIRCUIT_OPEN` and `NO_CONNECTION_AVAILABLE`. Step 4 has to decide how the connection test reports `CONNECTION_LOST`.
- New protocol anomaly kinds: `response.malformed-line` and `response.unexpected-code`. After such a response the connection is closed.

Left for later steps:

- `MODE READER` is not sent yet. To be decided in step 5, with the first reading command.
- Marking the server's credentials as invalid after `481` belongs to the server module (step 4). The adapter reports the failure type `AUTH_FAILED` and does not retry.

Tests and build:

- The certificates for the TLS tests are created when the tests run, with the `keytool` of the JDK. No key is stored in the repository.
- The test client trusts the certificate it sees when it connects to the mock server. On the development machine, security software intercepts TLS even for local connections and re-signs the certificate; the tests pass there through that interception.
- `.gitattributes` now forces LF line endings in every checkout. On Windows, a fresh checkout had CRLF, and the frontend format check failed.

**Step 1, project skeleton (2026-11-19)**

- A tenth module, `platform`, holds the technical code all modules share: data directory, database startup, logging helpers, API error handling. The generated jOOQ classes are an open module of their own.
- The first migration creates `wardroom_user`, so that the code generation and the startup tests have a real table. Step 3 uses it.
- A new, empty database is migrated without a backup; a backup is written only when an existing database has pending migrations.
- `WARDROOM_LOG_LEVEL` applies to Wardroom's own output. Libraries follow it only when it is stricter than `INFO`; at `DEBUG` they stay at `INFO`.
- Requests that fail with a `500` are logged with method, path and stack trace. Other failed requests (`4xx`) are not logged.
- The accent color is the blue of the logo, `#0374cf`.
- The runtime image installs `curl`, because the base image has no tool for the `HEALTHCHECK`.
- The frontend uses React Router 7 and TypeScript 5.9, not the newest major versions: ADR 0007 names React Router 7, and the API client generator requires TypeScript 5.
- Not verified locally: the build stage of the Dockerfile. The runtime stage was checked with the jar built by Maven. To be checked on the first build outside the development machine.
- When committing: mark `mvnw` and `docker/entrypoint.sh` as executable in Git (`git update-index --chmod=+x`), otherwise the CI workflow cannot start the wrapper.
