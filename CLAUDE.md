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
- Visibility: in a module's base package, `public` only for what other modules may use. In sub-packages such as `internal`, classes and constructors are package-private, Spring beans included; `public` there only when another package of the same module needs the type.
- SQLite with Flyway (plain SQL migrations) and jOOQ generated from the migrated schema; generated code is not committed. Migration rules in ADR 0004; before the first release, migrations may still be reset.
- In migrations, declare columns for times in milliseconds and for article numbers as `BIGINT`; jOOQ maps `INTEGER` to 32-bit numbers.
- Configuration via `@ConfigurationProperties` records with Bean Validation. Records for value objects and DTOs. Lombok only for `@RequiredArgsConstructor`, `@Slf4j`, `@Builder`; no `@Data` on domain classes.
- NNTP only through Wardroom's own `NntpClient` adapter around Apache Commons Net; circuit breaker with Resilience4j and retries with Spring Framework, as in ADR 0006 and 0026.
- API errors as Problem Details (RFC 9457).
- Variable, field and parameter names say what the thing is, in full words that match its type: `serverSettings` and `wireClient`, not `server` and `wire`. No abbreviations and no single letters, except `e` in a catch block and loop counters.

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
- All `/api/**` endpoints except setup and login need a session and, when they change state, the CSRF token. In integration tests, `TestBrowser` behaves like a browser (cookies and token); a test that is not about login can send a request with `.with(user("owner"))` instead.
- Field validation failures are thrown as `FieldValidationException` (platform module) and answered as `400` with field and rule.
- A module that stores security-sensitive data implements `AccountCreationCleanup` of the `auth` module (ADR 0017).
- A module that talks to the NNTP server gets its settings from `NntpServers` of the `server` module, for one call at a time, and reports rejected credentials there. A new server is announced with the event `ServerCreated`.
- A module that stores data of a server implements `ServerRemovalCleanup` of the `server` module, so the data is deleted with the server.
- The groups module owns subscriptions: `SubscriptionRepository` has the unsubscribe cleanup, the unread count and "mark all as read". The `sync` module stores articles, locations and ranges (`SyncedArticleRepository`). It reads the subscribed groups through `SubscribedGroups` of the groups module and implements its `GroupSynchronization`; work that must not run while a group is synchronized goes through `withoutSynchronization`. Inside the `sync` module, `RangeLoadService` runs "load older" and jump to date; they wait for a synchronization with `whenGroupIsFree` and store their articles as read.
- Threads are built in the `articles` module (`ThreadService`). An article without a `thread_root_id` has no place yet; code that stores an article or fills in a stub leaves the column empty and calls `Threads.recomputeGroup` after its transaction. Code that deletes the articles of a group calls `ThreadRecomputation.forgetThreadsOfGroup` before and `threadArticlesWithoutPlace` after the deletion, in the same transaction; only the threads with an article in the group are rebuilt. `recomputeAllThreads` is for the removal of a server. At the start, `ThreadStartup` threads every article without a place. The pure parts (`ThreadGraph`, `ReferencesParser`, `SubjectFallback`) have no database access.
- Ignore rules are in the `ignoring` module (spec 010). A rule is a filter and changes no article. Lists and counts that leave out what the owner ignores build their conditions with `IgnoreFilters`; the thread list (`ThreadListRepository`) and the unread counts (`SubscriptionRepository`) do. The author key of an article is computed with `AuthorKey` of the `articles` module by the code that stores the article or fills in a stub; at the start, `AuthorKeyStartup` fills in the keys that are missing.
- Posting is in the `posting` module (spec 009). `ArticleComposer` builds the article and `FlowedText` its body; both are pure. `PostingService` sends it with `NntpClient.post` on the interactive connection, once, stores it with `posted_from_newsgroup_id`, threads it with `Threads.threadArticle` and asks for a synchronization with `GroupSynchronization.synchronizeSoon`. A server can have a separate posting server (spec 002): then `NntpServers.postingServerSettings` returns its settings and the post goes there on a dedicated connection from `NntpConnector`; the pooled connections always lead to the host that is read from. A refusal of the server is a result (`NntpPostResult`), not an exception. An article the owner posted has no location until the synchronization finds it; code that looks for the articles of a group, or deletes articles without a location, has to think of these.
- Article content is fetched and cached by `ArticleContentService` in the `articles` module; `ArticleContentExtractor` takes the text out of an article and has no database access. Header values of overviews and of fetched articles are decoded with `NntpHeaderText`. The text of an article is never logged.
- The thread list, the thread page and "mark thread as read" are in `ThreadListService` and `ThreadListRepository` of the `articles` module. The cursor of the list is `ThreadCursor`.
- Instance settings come from `Settings` of the `settings` module, read for every use.
- The cache size limit (ADR 0023, ADR 0034) is cleaned by `CacheCleanup` in the `articles` module. It runs in the background at the start, after each synchronization run (`ArticleCache.cleanUpSoon`) and after the settings were saved (`CacheUse` of the `settings` module, which also gives the settings page the current use). Code that adds a column for cached content also empties it in `CacheRepository.clearContent`.
- A database transaction takes the write lock when it begins. Keep transactions short, and do not talk to the NNTP server inside one.
- Background refreshes and synchronizations are off in integration tests (`wardroom.group-list.automatic-refresh=false`, `wardroom.synchronization.automatic=false`); a test that needs them switches them on with `@TestPropertySource`, as `GroupListAutomaticRefreshTest` and `SynchronizationAutomaticTest` do. A test synchronizes a group with `POST /api/groups/{id}/sync` or `SynchronizationSchedule.startRun()`.
- Integration tests start with `TestInstallation.loggedInOwner`, which empties the tables. A test that needs pooled NNTP connections with short timeouts imports `SynchronizationTestSupport.ShortTimeouts`.
- `WARDROOM_ENCRYPTION_KEY_FILE` names a key file outside the data directory (ADR 0025); in tests, pass it as `--WARDROOM_ENCRYPTION_KEY_FILE=...`.
- A test that needs another NNTP connector than the application's (short timeouts, trust in the mock server's certificate) gets one from `TestNntpConnectors` and puts it in place with a `@Primary` test bean, as `ServerEndpointsTest` does.
- Frontend: API calls go through `api` in `src/api/client.ts`, which adds the CSRF token and handles ended sessions. Forms use `FormField` and `FormSelect`; settings sections are listed in `src/settings/sections.ts` and get their content in `src/routes.tsx`. The configured server comes from `useServers` in `src/server/serverState.ts`. The subscribed groups come from `useSubscriptions` in `src/groups/subscriptionState.ts`; subscribing and unsubscribing go through `SubscribeToggle`. Article text is drawn with `ArticleText` and loaded with `ArticleContent` in `src/articles`; the rules for quotes, links and flowed text are pure functions in `src/articles/textRules.ts`. Article text is untrusted and only ever put into the page as text. The discussion view is in `src/threads`: `ThreadList` and `ThreadCards` for the cards of a group, `ThreadArticles` for the thread page, `threadState.ts` for the list query and for showing read state in loaded lists without asking again. A loaded thread list is never refetched on its own. The date view of a group is `DateView`; the day in its address is read with `endOfDay` in `src/groups/days.ts`, because the view shows the group up to that day (ADR 0033). Ignoring and its toast with "Undo" go through `useIgnoring` in `src/ignoring/ignoreState.ts`, which asks for the thread lists and the unread counts again after a rule changed. Posting is in `src/posting`: `ArticleEditor` for a new discussion and a reply, `IdentityForm` for the posting identity, and the rules for quoting and target groups as pure functions in `quoting.ts`. The content of an article that is loaded for display is read with `useLoadedContent` in `src/articles/contentState.ts`. Group icons are drawn with `GroupIcon`; a change of the tables in Group Icons is also made in `src/groups/groupIconTable.ts`. The backend sends `null` where the generated types say that a field is absent. Tests mock the backend with the helpers in `src/test/backend.ts`.
- Other modules talk NNTP only through `NntpConnections` (pooled connections with connection limit, circuit breaker and retries) and `NntpConnector` (one dedicated connection, for the connection test). New NNTP commands are added to `NntpClient`.
- The mock NNTP server is `MockNntpServer` in `src/test/java/.../nntp/mock`; answers and faults are scripted with `MockReply`. Fixtures are in `src/test/resources/nntp` and keep their bytes.
- The shell tool removes one backslash of two in a here-document. Write files that contain backslashes with the file tool.
- To try the built jar by hand without touching real data: `java -jar target/wardroom-*.jar --server.port=8129 --wardroom.data-dir=<scratch directory>`. With another property name the application uses `/config`.
- shadcn/ui components are added with `pnpm dlx shadcn@latest add <name>` in `frontend/`.
