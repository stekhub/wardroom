# Implementation Plan

The order in which the MVP is built, and the status of each step. Each step is one branch and one pull request, small enough to finish and review in one sitting. The specifications define *what* is built; this plan only defines the order and the scope of each step.

## Status

| # | Step | Specs and ADRs | Status |
|---|---|---|---|
| 1 | Project skeleton | ADR 0002, 0003, 0004, 0007, 0008, 0009, 0024, 0027, 0028, 0029, 0030 | Done |
| 2 | NNTP foundation | ADR 0005, 0006, 0016, 0026; open question 1 | Done |
| 3 | Authentication and application shell | Spec 001; UI concept | Done |
| 4 | Server configuration and first-run wizard | Spec 002; ADR 0025; UI concept | Done |
| 5 | Newsgroup discovery | Spec 003; group icons | Done |
| 6 | Subscriptions | Spec 004 | Done |
| 7 | Synchronization core | Spec 005 | Done |
| 8 | Load older, load more, jump to date | Spec 005; ADR 0021, 0022 | Done |
| 9 | Threading | Spec 006 | Done |
| 10 | Article reading | Spec 007 | Done |
| 11 | Discussion view | Spec 008 | In review |
| 12 | Ignoring | Spec 010 | In review |
| 13 | Posting | Spec 009 | Not started |
| 14 | Cache size limit | ADR 0023; spec 005 | Not started; new ADR first |
| 15 | Activity of a group | Spec 003 | Not started; spec change first |
| 16 | Polish and documentation | UI concept; spec 001, 002; ADR 0032 | Not started |
| 17 | Release pipeline | ADR 0009, 0024 | Not started; discuss first |

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
- Dockerfile (multi-stage, amd64 and arm64) with the `PUID`/`PGID` entrypoint, `HEALTHCHECK` and `compose.yaml` as in ADR 0009. Built locally only; publishing is step 17.
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

- Spec 001 completely, except the installation documentation (step 16).
- Application shell from the UI concept: sidebar frame, routing, settings page frame with section navigation, toasts, skeletons.
- First-run wizard step 1 (account).
- Logo: the files in `assets/logo/`, a light and a dark variant. The MVP uses the light variant. The favicon is derived from it as a square image.

### 4. Server configuration and first-run wizard

- Spec 002 completely, including `WARDROOM_ENCRYPTION_KEY_FILE` (ADR 0025), except the documentation (step 16).
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

- Spec 007 completely: content fetch, Mime4j extraction, read marking, Content Security Policy, author icons, excerpts, leaving page.
- Before the links are built: propose to the owner a rule for where a URL in the text ends (a full stop or a closing bracket after it, angle brackets, an address broken over two lines). Spec 007 has none.

### 11. Discussion view

- Spec 008 completely: thread list, sorting, paging, date view, thread page, mark thread as read.
- "Load older discussions" in the normal view, with the `rangeId` of the page of thread cards. The backend exists since step 8.

### 12. Ignoring

- Spec 010 completely: rules, author key, filters in lists and counts, settings section "Ignored".

### 13. Posting

- Spec 009 completely, including posting availability in the connection test.

### 14. Cache size limit

- ADR 0023 and the cache parts of spec 005: measurement, cleanup job, setting with current use, warning.
- Switching the limit off (added by the owner on 2026-11-22): the owner can switch the cache size limit off in the synchronization section of the settings page. Without a limit, the cleanup does not run and nothing is deleted; the section still shows the current use. Before the implementation: propose a new ADR, because ADR 0023 has no way to switch the limit off, and the change of spec 005. To be decided then: whether the limit is on or off by default, how the setting and the settings response express "no limit", and what the section says about a cache that grows without a limit.

### 15. Activity of a group

Added to the MVP by the owner on 2026-11-22. Before the implementation: propose the change of spec 003 and of the data model to the owner. The direction:

- The group list shows how many articles a group gets per day, and can be sorted by it.
- The group list refresh already reads the high watermark of every group, so no further commands to the server are needed.
- Per group, two baselines are stored, an older and a younger one, each with a time and the high watermark at that time. No history is kept.
- At a refresh, the activity is the high watermark now minus that of the older baseline, divided by the time since the older baseline.
- When the younger baseline is 7 days old, it becomes the older one, and the values of this refresh become the younger one. The activity is therefore a plain count over the last 7 to 14 days.
- The activity does not depend on how often the list is refreshed. After a time without refreshes it is the average over that longer time.
- The first baseline is stored with the first load of the group list.
- A value is shown once the older baseline is at least 24 hours old, because a shorter time follows the time of day. Until then the list says that activity is shown after one day. In the first week the value is rough.
- If the high watermark is below a baseline, the group was renumbered: both baselines start over and the group has no value.
- Groups without a value are sorted last.
- The high watermark of the group itself cannot serve, because the synchronization of a subscribed group moves it.
- The value is approximate, because cancelled articles and spam count too.
- A moving average was considered and dropped: it depends on the whole history of refreshes, a burst fades out slowly instead of dropping out, and its value cannot be checked.
- To be decided with the spec change: whether the activity is shown as a number or as levels.

### 16. Polish and documentation

- Quick switcher (`Ctrl+K` / `⌘K`), settings section "About", accessibility pass against WCAG 2.2 AA.
- Shorter sessions without "Remember me" (decided by the owner, part of the MVP): a session from a login without "Remember me", and the session after setup, ends after 30 minutes of inactivity. A session with "Remember me" keeps the 7 days. The server enforces both, so the option no longer depends on the browser dropping the cookie. Change spec 001 with the implementation. To be decided then: whether requests the open page sends on its own count as activity, and how an article that is being written survives a session that ended (spec 009).
- Application version in backup names (ADR 0032): store the version at every start, name the backup after the stored version, log both versions, and adapt the cleanup of old backups to the new file name. Add the section on going back to an earlier version to the user guide.
- README: introduction, quick start with the reference Compose file, first-run wizard, link list to the user guide (ADR 0031).
- User guide (`docs/user-guide.md`): backups, updates, password reset (spec 001), lost key file (spec 002), and an advanced section on keeping the encryption key outside `/config` (ADR 0025, 0031).
- The README and the user guide were written early, on 2026-11-19. In this step: check both against the finished application and replace the screenshot placeholder in the README with a real screenshot.

### 17. Release pipeline

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

**Step 12, ignoring (2026-11-22)**

Built:

- The `ignoring` module: the table `ignore_rule`, the endpoints `GET`, `POST` and `DELETE /api/ignore-rules`, and `IgnoreFilters`, the conditions the other modules build their queries with.
- `article.author_key` with its index. The synchronization and the content fetch store it when they store an article or fill in a stub.
- The thread list leaves out ignored threads and threads whose card article is by an ignored author. "● N new" on a card leaves out articles by ignored authors.
- The unread counts in the sidebar leave out articles by ignored authors, the articles of ignored threads, and the articles of threads that are hidden because of their card article.
- The thread response has `ignored`; each of its articles has `ignoredAuthor`.
- Frontend: "Ignore thread" on the cards and in the header of the thread page, "Ignore author" in a menu of each article, the toast with "Undo", the line "Ignored author · {name}" with "Show", the notice "You ignore this thread" with "Stop ignoring", and the settings section "Ignored".

Deviations and decisions, open to a veto:

- **The author keys of stored articles are filled in at the start, not in the migration.** Spec 010 names a "migration with backfill". Migrations are plain SQL (ADR 0004), and the key needs the rules for addresses in `From`, which SQL cannot express. `AuthorKeyStartup` computes the missing keys at every start, in batches of 1,000; a copy of a database with 101,006 articles took 3.6 seconds, once.
- **The thread response also has `ignoreRuleIds`.** Spec 010 gives it only `ignored`. "Stop ignoring" on the thread page must remove the rule, and the page could not know which one. More than one rule can ignore a thread, when two ignored threads become one; the button removes them all.
- **Ignoring a thread that is already ignored returns the rule that ignores it,** also when the root of the thread has changed since. Otherwise a second rule would be created for the same thread.
- **Labels.** The label of an author rule is the display name, as the thread page shows it; without a name, the address. The label of a thread rule is the subject of the root; when the root is a stub, the subject of the article the owner chose; without any, "(no subject)".
- **The menu of an article** is a button "More actions" with one entry, "Ignore author". Spec 010 speaks of "each article's menu"; none existed. Step 13 adds its actions there or beside it.
- **An article by an ignored author that is shown again** stays shown until the page is left, carries the badge "Ignored author", and is not marked as read. It has no "Ignore author" in its menu.
- **A rule change asks for the loaded thread lists again.** A loaded list is otherwise never asked for again (spec 008); here it must be, because cards appear, disappear or change their count. The list can change its order when a synchronization ran since it was loaded.
- **The toast with "Undo" stays 8 seconds,** twice as long as other toasts.
- **Rules are kept when a server is removed.** They apply in all groups and match by value (data model).
- **Unread counts with rules are slower.** Without any rule, the count is the query it was. With a rule, each group is counted with a query that finds the card article of every thread. For a few dozen groups this is not noticeable; it is asked for every 30 seconds.

Verified:

- `./mvnw verify` passes: 543 backend tests and 197 frontend tests.
- Tried by hand against a fake NNTP server with 500 articles: ignoring a thread from its card (card gone, unread count 500 to 481), the notice on the thread page and "Stop ignoring", "Ignore author" in the menu (the line "Ignored author · Ada Example", unread count 495 to 338, the rule in the settings section), and removing the rule there (count back to 495).
- Tried by hand by the owner on 2026-11-22: everything works.
- Not tried by hand by the AI assistant: "Undo" in the toast and "Show" on an ignored line.

For later steps:

- Step 13: reply buttons go into or beside the menu "More actions" of an article. An article posted with Wardroom needs its author key when it is stored.

**Step 11, discussion view (2026-11-21)**

What was built:

- Backend, module `articles`: the thread list with the three sort orders, the date view and keyset paging; the thread endpoint; "mark thread as read". One query per page computes the cards of a group.
- No migration: the index on `article.thread_root_id` that spec 008 names exists since step 9.
- Frontend: the group view with sort tabs, thread cards, loading of further pages on scrolling, "Load older discussions", the "N new discussions · show" button and the empty states; the date view with its threads up to the chosen day (ADR 0033); the thread page with nesting, collapsing, stubs and crossposts; "Mark thread as read" on cards and on the thread page.
- A new view opens at the top, and going back returns to where the view was left.
- The sidebar marks a group also while one of its threads is open.

Not built, because it belongs to a later step:

- "Ignore thread" on cards and in the thread page header, and "Ignore author": step 12.
- Reply buttons: step 13.

Decisions made during the step, open to a veto. None of them is written into a specification or the data model yet.

Backend:

- **The author key is computed when it is asked for.** Spec 010 stores it in `article.author_key`; that column comes with step 12. Until then the key is derived from `from` for every card and article. The result is the same.
- With `until`, a `rangeId` the group does not have is answered with `400`.
- A cursor belongs to one order. A cursor of another order is answered with `400`.
- `highWatermarkReached` is true when the range is the newest one of the group, as `atNewest` of the extend endpoint. `lowWatermarkReached` is true when the range begins at the stored low watermark. Both are false while the group has no range.
- In the date view, a thread whose card article has no date is not shown: it cannot be "on or before" a day. Spec 008 says so now.
- Order of replies to the same parent: by date, oldest first. Replies without a date follow the dated ones, by article number in the group. Spec 006 says "placed by article number", which does not say where they go between dated replies.
- `read` is false for a stub. The frontend shows no "new" marker on a stub.
- "Mark thread as read" answers `404` for an unknown group and `409` for a group that is not subscribed, as "mark all as read" does. The two reading endpoints answer `404` for both, as spec 008 says.
- The excerpt of a card is computed from the first 20,000 characters of the cached text, so a very long article is not read completely for every card.

Frontend:

- **"N new discussions"** counts the threads that are new or have more messages than shown, among the first 50 threads of the current order. They are asked for aside; the shown list is not touched. A synchronization that changed nothing shows no button. An empty list is loaded again at once.
- **The "new" marker of an article stays while the thread page is open,** although the article is marked as read when it is displayed. Otherwise the marker would vanish before the owner sees it. "Mark thread as read" removes the markers.
- The name shown for an author is the name part of `From`: "Jane Doe" for `Jane Doe <jane@example.org>`. Spec 008 says that the frontend does not parse `from` for the icon; for the name there is no other source.
- "Mark thread as read" on a card is shown only when the thread has unread articles.
- A card shows "latest …" only when the thread has more than one message.
- Further pages load when the end of the list scrolls into view. A button "Show more discussions" is there as well, for the keyboard and for the case that scrolling does not trigger the loading.
- A vertical line belongs to the article whose replies it marks; a click on it collapses that article. A reply deeper than level 6 has the six lines of its topmost ancestors.
- A collapsed article without replies says "hidden" instead of "0 replies hidden".
- The thread page shows the subject once, as its heading: the subject of the first article that is no stub. The single articles show no subject.
- The link back to the group goes back in the browser history when the thread was opened from the list, so the list is where it was left. Opened by its address, it leads to the group view.
- After a stub was fetched, the thread is asked for again, so that the author and date of the article appear.
- After "Load older discussions" the loaded pages are asked for again in place. The list keeps its scroll position.
- The date view says "No discussions up to this day" when its range has none.
- While the jump runs, the date view shows a turning icon with "Searching the server for this day…" above card placeholders. The UI concept prefers skeletons to spinners; the search can take seconds, so the view also says what it waits for, as the first synchronization does. Asked for by the owner.

Findings:

- **Earlier browser checks used `C:\config` as their data directory.** The start command of the AI assistant had a wrong property name (`wardroom.data-directory`), so the application fell back to its default `/config`, which is `C:\config` on this machine. As far as can be told, the directory holds only test data of these checks; its database is from 2026-11-19. It was not changed in this step and not deleted. The owner decides whether it goes. The right property is `wardroom.data-dir`.
- The browser pane of the AI assistant does not draw pages while it is hidden. Loading on scrolling does not happen there, so it was replaced by a stand-in for the check; see below.
- **Found by the owner: the date view showed the group from the chosen day forward.** In a quiet group, a view for 2010 began with discussions from 2012. The owner decided that the chosen day is the upper end: the view shows the discussions that began on or before that day, newest first, with "Load older discussions" as in the normal view. This is ADR 0033, which supersedes the direction of the jump and "load more" in ADR 0021; specs 005 and 008 and the UI concept are changed to match. In the code: the jump takes `until` (the end of the day) and loads the articles up to there; the date search goes on until fewer than 20 numbers remain; the jump result is the range, or the date of the oldest article when the day is before it; "load more" and the direction of the extend endpoint are removed; the address is `?until=YYYY-MM-DD`. Tried in a browser against the local test server: a jump that fetched 500 articles up to the day after 7 probes, a day before the oldest article with its link, and "Load older discussions" down to the low watermark.
- **Found by the owner: going back from a thread to a date view took seconds.** The view forgot its jump when it closed and ran the date search against the server again. Now the jump of a date view is kept as long as its list, 30 minutes, and going back shows the view at once. Only the date view of the last jump is kept per group, because a later jump can join the range of an earlier one with its own. "Load older discussions" in the normal view can also merge the range of a kept date view into the newest range; when the backend then answers that the range is gone, the date view runs its jump again and continues with the new range. Unsubscribing drops the kept lists and jumps of the group. Accepted by the owner on 2026-11-22; spec 008 is changed to match.
- **Found in the review before the pull request: "Load older discussions" in the normal view could fail after a jump to date.** A jump that ends directly below the newest range joins the two, and the joined range has the ID of the jump. A normal view that was loaded before still had the old ID, and its "Load older discussions" answered with an error. Now the list is asked for its range again, and the step is tried once more with that range. A frontend test covers it. Open to a veto: spec 008 does not describe this case.
- **Found by the owner: articles that were stored before step 9 were never threaded.** Every reply was shown as a discussion of its own. Threads were computed only after a synchronization had stored new articles, so a group without new articles kept its articles without a place. A threading that failed was not repeated either, although spec 006 says that the next synchronization retries. Fixed in this step: the start of the application threads every article without a place, and a synchronization that fetches nothing threads the articles of its group that have none. Two tests cover it. Tried on a copy of a database with 2,582 such articles: the start threaded them in 0.7 seconds.
- The thread list of a group with 100,000 articles in 20,000 threads takes 160 ms per page on the development machine, in every order.

Verified and not verified:

- `./mvnw verify` passes: 533 backend tests and 188 frontend tests.
- Tried by hand in a browser, against the built jar with a scratch data directory and a local test NNTP server with 1,200 threaded articles: the wizard; the thread list in all three orders; paging through all pages without a thread shown twice; "Load older discussions"; opening a thread; text, quotes and links of 31 articles; indentation up to level 6; collapsing by line and expanding; the unread count in the sidebar going down; going back to the list at the same scroll position, with the card updated; a thread whose first article the server does not have; the date view.
- Not tried by hand: the loading when something scrolls into view, for further pages and for article text. The browser used for the check did not report scrolling, so a stand-in that reports everything as visible was put in its place. The owner should try this in a normal browser.
- Not tried by hand: the "N new discussions" button, "Mark thread as read", a crossposted article. Component tests cover them.
- Not verified: real articles from a real server.

Left for later steps:

- Step 12: `article.author_key` with backfill replaces the computation in `AuthorKey`; "Ignore thread" and "Ignore author" go where the cards and article rows have their actions; the list and count queries get the ignore filters.
- Step 13: reply buttons on the thread page.
- If real threads show that level 6 is too narrow or too flat: a marker for what a deep reply answers, or a view that starts at a reply.

**Step 10, article reading (2026-11-20)**

What was built:

- Migration V8: the content columns of `article`.
- NNTP adapter: `ARTICLE <message-id>` with the size limit of 10 MB.
- Backend, module `articles`: body extraction with Mime4j, character set fallback, parser limits, omitted parts, yEnc and uuencode blocks taken out of the text, the three endpoints of spec 007, filling in a stub, and the card excerpt function.
- Threading: `In-Reply-To` is read from the stored header block and used when an article has no `References`. Such an article is left out of the subject fallback. An article is threaded again after its content was fetched, when it was a stub or its `In-Reply-To` is known now.
- Content Security Policy and `Referrer-Policy: no-referrer` on all responses.
- Frontend: the text of an article (quotes, signature, links, `format=flowed`, fixed width), the component that loads the content when it scrolls into view and marks the article as read, the author icon, and the leaving page at `/leaving`.

Not visible yet:

- The components for article text, content and author icons are built and tested, but no page uses them before the thread page of step 11. The leaving page can be opened by its address.
- The excerpt function is built and tested; the thread cards of step 11 use it.

Accepted by the owner during the step, and written into spec 007:

- The rule for where a link ends: it runs to the next whitespace, `<`, `>` or double quote; punctuation at the end is cut off; a closing bracket is cut off when the address has no opening one; an address in angle brackets that is broken over up to 5 lines is one link; without angle brackets a link ends at the line end.

- Three columns that the data model did not have: `body_flowed`, `body_delsp` and `body_truncated`. The API returns `flowed` and `truncated`, and the text is unwrapped at display time, so both have to be stored with the text. They are in the data model and in spec 007 now.
- One field more in the API: `delSp`. A sender can mark flowed text with `DelSp=yes`; then the space before a line break is not part of the text. Without the field, such text gets wrong spaces when it is unwrapped. It is in spec 007 now.

Decisions made during the step, open to a veto. None of them is written into a specification or the data model yet:

- `truncated` is true for every limit: text longer than 1 MB, more than 100 parts, nesting deeper than 10, or an article longer than 10 MB. The article then shows one notice, "This article is too long or too complex. Only a part of it is shown."
- An article longer than 10 MB is cut off after the last line that fits. The connection is closed, because the rest of the article is still on its way.
- A character set of `us-ascii` counts as not declared, so the fallback to UTF-8 and windows-1252 applies. Text that claims ASCII often has other characters.
- The first `text/plain` part is shown also when it is marked as an attachment. Spec 007 and ADR 0014 make no exception.
- A part inside an attached message (`message/rfc822`) counts like any other part.
- An HTML part next to a plain text is not named in the attachment notice. Only an article without plain text says "HTML message, not displayed".
- The size of an omitted part is its decoded size. For yEnc it is the size the block names; for uuencode it is computed from the encoded length.
- `PUT /api/articles/{id}/read` answers `204` for a stub and stores nothing. A stub has no text that could have been displayed.
- A stub is filled in with `Subject`, `From`, `Date`, `References` and `Lines` from its headers; `bytes` is the size of the fetched article. It is not marked as read by the fetch; the frontend does that when the text is displayed.
- The server of an article is the server of a group it is located in. A stub has no location; its server is the one of another article of its thread.
- While the credentials are known to be invalid, the content endpoint answers `502` without asking the server, as spec 005 does for synchronizations.
- A failed recomputation of the thread after a content fetch does not fail the request. The content is stored, and the article keeps the place it had.
- Content Security Policy: `default-src 'self'` is added as the base, so that fonts, connections and everything else not named in spec 007 also come only from Wardroom itself.
- The leaving page shows the address as the browser reads it, not the text of the parameter. So what is shown is what "Continue" opens.
- The leaving page is outside the application frame: it has no sidebar, and it is shown also before a server is configured.
- The excerpt is computed from the stored text when it is asked for. It is not stored.
- `NntpHeaderText` in the `nntp` module is public: overviews and fetched articles decode header values the same way.

Findings:

- **An article without a location goes with every unsubscribe cleanup.** The data model deletes "articles without any remaining location". A stub that was filled in by reading it has no location, so unsubscribing from any group removes it and its cached text. It becomes a stub again and is fetched again when it is read. The threads stay correct: the cleanup now also recomputes the threads of such articles. If this is not wanted, the rule in the data model has to change.
- Swagger UI of the development profile works with the Content Security Policy.
- The shell tool of the AI assistant removes one backslash of two in a file written with a here-document. One regular expression was wrong because of that; a test found it. Files with backslashes are written with the file tool since then.

Verified and not verified:

- `./mvnw verify` passes: 514 backend tests and 161 frontend tests.
- Tried by hand in a browser, against the built jar with a scratch data directory: the pages load without a violation of the Content Security Policy; the leaving page shows domain and address, "Continue" has the target as its address, and an address with `javascript:` offers no "Continue"; Swagger UI of the development profile still works.
- Not tried by hand: reading an article. No page shows article text before step 11.
- Not verified: real articles. The extraction is tested with built articles, not with real ones from a server. Step 11 is the first chance to see real text.
- Not verified: an article of more than 10 MB from end to end. The limit is tested in the adapter with a small limit.

Left for later steps:

- Step 11: the thread page uses `ArticleContent`, `ArticleText` and `AuthorIcon`; the thread cards use `Excerpt.of`. Judge quote colors, the collapsed quotes and the notices with real articles. `onRead` of `ArticleContent` is there to clear the "new" marker.
- Step 12: `author_key`, which `AuthorIcon` takes.
- Step 13: articles posted with Wardroom that wait for their location must be excepted from the cleanup (spec 009); the new part of the cleanup that looks at articles without a location needs the same exception.
- Step 14: the cache cleanup removes content; the partial index on `date` for articles with cached content (ADR 0023) is not built yet.

**Step 9, threading (2026-11-20)**

What was built:

- Migration V7: the columns `parent_id` and `thread_root_id` of `article`, with an index each.
- Backend, module `articles`: parsing of `References`, subject normalization, the threading function, the subject fallback, and the service that stores the result.
- Threads are recomputed after every stored chunk of a synchronization, after "load older", "load more" and a jump. After unsubscribing and after a renumbering, the threads that had an article in the group are recomputed; after removing a server, all threads.
- The queries of spec 006: the threads of a group with message count, latest activity and card article, and the thread root of any article.
- Nothing in the frontend. No endpoint is new.

Not built, because it needs a later step:

- `In-Reply-To` is known only when the content of an article is fetched (step 10). The threading function uses it already and is tested with it; nothing passes it in yet.
- The hook "after an article's content is fetched" (step 10).
- The thread list and the thread page that use the queries (step 11).

Decisions made during the step, open to a veto:

- `thread_root_id` of a thread root is the ID of the article itself. An article without a `thread_root_id` has no place yet: new articles are stored that way, and a stub that is filled in loses its thread root. The threading picks up exactly these articles. So a recomputation that failed is repeated by the next one, as spec 006 asks.
- The threading runs in a transaction of its own, after the transaction that stored the chunk. A failure of the threading does not lose the chunk.
- After a chunk, the parent of a stub comes from the article that was stored first, as spec 006 says ("the first article that created it"). A complete recomputation threads by article number. The two differ only when two articles list different Message-IDs before the same missing article, and the newer one was fetched first.
- Unsubscribing and a renumbering recompute only the threads that had an article in the group (accepted by the owner on 2026-11-20; data model and spec 006 are changed). Before the deletion, these threads lose their stubs, and their articles that are also in another group lose their place. After the deletion, every article without a place is threaded, as after a chunk. It runs in the transaction that removes the articles. If it fails, the removal fails with it.
- The unsubscribe cleanup no longer deletes stubs itself. As a safeguard, a stub without a thread root is deleted when threads are recomputed after a removal.
- A complete recomputation deletes all stubs, takes the place away from every article, and threads group by group, lowest group ID first, each by article number. It is used when a server is removed, and a test compares the result of unsubscribing with it.
- Subject fallback: the articles of a group are decided oldest first, so the result does not depend on the order in which they were fetched. Two replies without references attach to the original post, not to each other; without the original, the second attaches to the first.
- Subject fallback: only articles with a date are attached, and only thread roots with a date are matched. A crossposted article is decided in the group with the lowest ID only; otherwise two groups could move it back and forth. A subject that is only a reply prefix matches nothing.
- An article "has no References" when the header has nothing of the form `<…>`. An article whose header has such tokens but none that is usable is a thread root, and the subject fallback does not apply to it.
- Of a Message-ID that appears twice in `References`, the first place counts.
- The card article of a thread without its root in the group is the oldest article by date; articles without a date come last, by article number.
- A link that would close a cycle is logged as a `PROTOCOL_ANOMALY` with the new kind `references.cycle`.
- `parent_id` and `thread_root_id` are foreign keys with `ON DELETE SET NULL`, so the cleanup can delete articles that others point to.
- The threading is in the module `articles`. The groups module asks for the recomputation through its interface `ThreadRecomputation`, which `articles` implements; so `groups` does not depend on `articles`.
- Both indexes of spec 006 are in V7. With this, the migration task of spec 008 for the index on `thread_root_id` is done.

Findings:

- Measured with 100,000 articles in one group, 12 per thread, on the development machine: threading one chunk of 1,000 new articles takes about 65 ms; a recomputation with nothing new, which is the subject fallback of the group, 70 ms; the thread figures of the group 150 ms; a complete recomputation 7.5 seconds.
- A complete recomputation grows with the number of all stored articles, and nothing else can write while it runs. This is why unsubscribing recomputes only the threads of the group. Measured with 100,000 articles in one group and 5,000 in a second one, a tenth of whose threads are crossposted: leaving the small group takes 0.3 seconds, leaving the large one 1.2 seconds, most of it the deletion itself; a complete recomputation of the same data takes 5.4 seconds.
- A thread whose articles were all located in the unsubscribed group, except some replies, keeps those replies: the removed articles become stubs again.

Verified and not verified:

- `./mvnw verify` passes: 459 backend tests and 125 frontend tests.
- The unit tests of spec 006 are in `ThreadGraphTest`, `ReferencesParserTest` and `SubjectFallbackTest`; the integration tests in `ThreadServiceTest` and `ThreadRecomputationTest`.
- Not tried by hand: nothing of this step is visible before step 11.
- Not verified: real articles. Real `References` and subjects are messier than the test data.

Left for later steps:

- Step 10: pass `In-Reply-To` to the threading and leave an article with a known `In-Reply-To` out of the subject fallback; thread a stub that was filled in by a content fetch. Such an article has no location, and the threading finds its articles through their locations, so step 10 needs an entry point by article.
- Step 11: the thread list sorts and pages in SQL; `ThreadRepository.findThreads` is the starting point.
- Step 13: articles posted with Wardroom that wait for their location have none either.

**Step 8, load older, load more, jump to date (2026-11-20)**

What was built:

- Backend, module `sync`: the date search, jump to date, and extending a range downwards and upwards, with `POST /api/groups/{id}/jump` and `POST /api/groups/{id}/ranges/{rangeId}/extend`.
- A group can have several synchronized ranges now. Ranges that touch are merged.
- Articles that these steps store arrive read; a stub they fill in is read as well (ADR 0022).
- Frontend: the date picker in the group header, and the date view at `/groups/{id}?from=YYYY-MM-DD` with its bar, the note about the oldest article and "Load more".

Not built, because it needs a later step:

- "Load older discussions" in the normal group view. The button belongs at the end of the thread list (spec 008), and it needs the ID of the newest range, which no endpoint returns yet. See the first finding. The backend for it exists and is tested.
- The date view shows no threads yet (step 11). Until then it says which article numbers are loaded.
- Threads are not recomputed after a step (step 9).

Decisions made during the step, open to a veto:

- Load more and jump to date fetch nothing above the newest range. What is there is new and belongs to the synchronization, which stores it unread. Otherwise a jump to a recent date would mark new articles as read and leave a gap below them that no synchronization fills. So the date search looks between the low watermark and the end of the newest range, and `atNewest` is true for the newest range of the group.
- A group without any range answers jump with `409`: "This group has not been synchronized yet. Refresh it first." A range from a jump would otherwise count as the newest range, and the next synchronization would fetch everything from there to the high watermark.
- A step waits for a running synchronization of its group, for at most 30 seconds, and then answers `409`. Spec 005 says that they never run at the same time, and not what the second one does. Unlike unsubscribing, a step does not cancel the synchronization.
- A step has a single attempt. ADR 0026 says that direct user actions are not retried.
- A step fetches everything before it stores anything, and stores it in one transaction. With the largest depth these are 10,000 overviews in memory. This is what "nothing is stored" on a failure needs.
- A step does not store the watermarks it reads, and marks nothing as expired. The synchronization does both.
- A renumbered group (high watermark below the end of the newest range) answers `409`: "The server has renumbered this group. Refresh it first." The synchronization does the reset.
- A group the server does not have (`411`) answers `502`. A rejected login marks the credentials and answers `409`.
- A merged range keeps the ID of the range that was extended. After a jump it is the ID of the new range.
- `clampedToOldest` is true when the oldest article is dated after the chosen time. `startDate` is the date of the first stored article from the start on that has a date; it is `null` when there is none.
- The start of a jump is the lower end of the last search interval, as spec 005 says. It lies up to one depth before the first article of the day. In the check by hand, with four articles per day, it was ten days before.
- In the date search, windows that reach the end of the searched numbers without a dated overview count as "nothing on or after the date there". Only five empty windows in a row fail the search. A group whose oldest articles have no date fails it as well.
- A day whose date is in the future cannot be chosen in the date picker.
- Frontend: the note about the oldest article is left out when that article is from the chosen day itself.
- The property `wardroom.synchronization.load-wait` (30 seconds) is how long a step waits for a synchronization. The tests shorten it. It is not documented for people who run Wardroom.

Findings:

- **A gap in spec 008:** the frontend needs the ID of the newest range to offer "Load older discussions" in the normal view, and no endpoint returns it. The page of thread cards has `lowWatermarkReached` and `highWatermarkReached`, but no range. The owner accepted the proposal: the page of thread cards gets a field `rangeId`, the range the list shows. It is written into spec 008 and comes with step 11.
- Spec 005 answers a failed jump or extend with `502`, and until now no endpoint did. `ServerFailedException` in the `sync` module is the first; its text is the cause as the NNTP adapter words it.
- The shadcn/ui component `popover` is new.
- After choosing a day, the tooltip of the date picker button shows again, because the focus returns to the button. This is how the component library behaves; it goes away with the next click or key.

Verified and not verified:

- `./mvnw verify` passes: 382 backend tests and 125 frontend tests.
- Tried by hand in a browser with the built jar against a small local server without TLS, with 3,000 articles over two years: a jump to a day a year ago, "Load more" up to the merge with the newest range, a jump to a day before the oldest article, opening a date view whose start is stored already, and the unread count, which stayed the same.
- Not tried: a real server, a group with millions of articles, a group with wrong or missing dates (only in tests), load older (only in tests, see above).

Left for later steps:

- Recompute threads after load older, load more and jump to date (step 9).
- "Load older discussions" in the normal view, the threads of the date view, and the range ID the thread list needs (step 11).

**Step 7, synchronization core (2026-11-20)**

What was built:

- NNTP adapter: `GROUP`, and `OVER` or `XOVER` with the field order of `LIST OVERVIEW.FMT`. Overview lines are parsed in the adapter: encoded words in `Subject` and `From` are decoded, the date becomes UTC milliseconds.
- Backend, module `sync`: the synchronization of one group (first and incremental, in chunks, with ranges, expiry and renumbering), the schedule, and `POST /api/groups/{id}/sync`.
- Backend, module `settings`: the table `app_setting`, and `GET` and `PUT /api/settings`.
- Migration V6: the columns `subject`, `from`, `date`, `references`, `bytes` and `lines` of `article`, and the table `app_setting` with its three keys.
- Subscribing starts the first synchronization in the background. Unsubscribing cancels a running one. The subscription list says which groups are being synchronized. With this, the two tests of spec 004 that step 6 left open exist.
- The schedule of the group list uses the setting `sync.group_list_interval_hours`.
- Frontend: the refresh button in the group header, and the settings section "Synchronization" with its three fields.

Not built, because it needs a later step:

- Load older, load more and jump to date, with their two endpoints (step 8).
- Threads are not recomputed after a synchronization (step 9).
- The cache size limit: the setting `cache.max_size_gb`, the cleanup job, and the fields `maxCacheSizeGb`, `cacheUsedBytes` and `cacheLimitUnreachable` of the settings (step 14). Until then the settings have three fields.
- The columns of `article` that belong to other specs: `author_key` (spec 010), `parent_id` and `thread_root_id` (spec 006), the content columns (spec 007), `posted_from_newsgroup_id` (spec 009). The step that adds `author_key` has to fill it for the articles that are stored already; spec 010 says so.
- The group view still has only its header. It says "The threads of this group are not shown yet." until step 11.

Decisions made during the step, open to a veto:

- The first synchronization is fetched in chunks of 1,000 as well, oldest first. Spec 005 names chunks only for the incremental one. So a first synchronization with a large depth that is interrupted keeps what it has.
- When the low watermark is above the end of the newest range, the synchronization asks from the low watermark on. The range is extended without a gap. The numbers in between do not exist on the server any more.
- An empty group gets no range. Its next synchronization is a first synchronization again.
- A known article that is not a stub keeps its stored fields. Only a stub is filled in.
- An article number belongs to one article. If another article has a location under the number of a new overview, that location is deleted.
- The manual refresh uses the connection that is kept for interactive actions, and is retried like background work. The first synchronization after subscribing uses a background connection.
- The schedule looks every 30 seconds whether a run is due. The first run is due 30 seconds after the start. When a run was last due is held in memory. A changed interval counts from the time a run was last due.
- A run leaves a server alone whose credentials are invalid, and `POST /api/groups/{id}/sync` answers `409` then. Spec 005 lists "`409` not subscribed or credentials invalid".
- A rejected login (`481`) marks the credentials and stops the run for the server. The group gets no `last_sync_error`; the notice about the credentials says what is wrong.
- A run that finds the circuit of a server open leaves its groups as they are, as spec 005 says. A manual refresh that finds it open writes the error to the group, so the owner sees why nothing happened.
- Subscribing clears `last_synced_at` and `last_sync_error` of the group (left open in step 6).
- Unsubscribing waits for the chunk that is being fetched and stored. In the worst case this is the read timeout of 30 seconds.
- When the server is removed while a group is synchronized, the synchronization notices before it stores its next chunk, and ends.
- Header text outside encoded words is read as UTF-8, otherwise as ISO-8859-1, like group names. A group name is sent to the server as UTF-8.
- `bytes` and `lines` that are not numbers are stored as empty. A date is read leniently; what cannot be read is empty, as spec 005 says.
- `LIST OVERVIEW.FMT` is left out when the capability list has a `LIST` line that does not name it. An answer that does not name all seven fields is logged, and the order of RFC 3977 is used. The order is kept in memory per server, until a restart or until the server is saved.
- `420` in answer to `OVER` counts like `423`: no articles.
- New protocol anomaly kinds: `overview.missing-message-id`, `overview.unusable-format` and `group.malformed-response`.
- A stored setting that is missing, is no number or is outside its range counts as its default.
- The property `wardroom.synchronization.automatic` switches off the schedule and the synchronization after subscribing. It exists for the tests of other features and is not documented for people who run Wardroom.
- Frontend: the refresh button stays usable while a synchronization runs; its icon turns. Pressing it again does nothing, because the backend answers `202` for a running synchronization.
- Frontend: the field for the first synchronization depth says that a change applies to groups subscribed afterwards. Load steps come with step 8.

Findings:

- Apache James Mime4j is a dependency now (ADR 0002 names it). It decodes the encoded words and reads the dates.
- Two column names of the data model, `from` and `references`, are SQL keywords. They are quoted in the migration.
- The groups module and the synchronization need each other. The groups module offers `SubscribedGroups` and expects a `GroupSynchronization`; the `sync` module uses the one and implements the other. So only `sync` depends on `groups`.
- The mock server cannot test a login without TLS. The rejected login is therefore tested without Spring, with replaced collaborators, in `SynchronizationFailureTest`.

Verified and not verified:

- `./mvnw verify` passes: 348 backend tests and 116 frontend tests.
- Tried by hand in a browser with the built jar against a small local server without TLS that knows only `XOVER`: subscribing with the first synchronization, the running icon and the unread count in the sidebar, the failure icon for a group the server does not have, the refresh button with new articles, saving the settings, and a run of the schedule.
- Not tried: a real server, TLS and login, a group with more than a few thousand articles, renumbering and expiry (only in tests).

Left for later steps:

- Recompute threads after each synchronization and after the renumbering reset (step 9).
- Fill `author_key` for stored articles when the column is added (step 12, or step 10 if author icons need it first).
- The cache fields of the settings and the cleanup after each run (step 14).

**Step 6, subscriptions (2026-11-20)**

Accepted by the owner after the review, and written into spec 004: the field `description` of a subscription, and `204` for subscribing to a subscribed group that the server has removed.

What was built:

- Backend: the tables `subscription`, `article`, `article_location`, `read_state` and `synced_range` (migration V5), the four endpoints of spec 004, the unsubscribe cleanup, the unread count and "mark all as read". The group list of spec 003 now says which groups are subscribed.
- Removing the server now also deletes subscriptions, article locations, articles without a location, their read state and the synchronized ranges.
- Frontend: the subscribe toggle in the discover rows and in the group header, the confirmation before unsubscribing, the subscription list in the sidebar, and the group view with its header and "Mark all as read".

A problem in the order of the steps, and how it was handled:

- Spec 004 needs tables that the plan gives to step 7. Its migration task names `read_state`, which points to `article`. Its tests for the cleanup, the unread count and "mark all as read" need `article` and `article_location`, and the cleanup deletes rows of `synced_range`. Spec 005 lists these three tables in its own migration task.
- Handled, open to a veto: step 6 creates all five tables. `article` has only the columns this step uses: `message_id` and `is_stub`. Step 7 adds the other columns of the data model in its own migration. The tests of this step put articles into the database themselves.

Not built, because it needs a later step:

- Subscribing does not start a synchronization, and unsubscribing has none to cancel (step 7). The test "unsubscribing during a running synchronization leaves no locations behind" and the half of the first test that says "starts the first synchronization" come with step 7.
- `sync.running` is always `false`. `lastSyncedAt` and `error` are read from the group row, which nothing fills before step 7. The sidebar already shows the three status icons when the data says so.
- The unread count leaves out ignored articles and hidden threads only from step 12 on.
- The cleanup does not recompute threads (step 9) and does not keep articles that were posted and wait for their location (step 13).
- The group view has only its header. Refresh and jump to date come with steps 7 and 8, the threads with step 11.

Decisions made during the step, open to a veto:

- The subscription in the answer of `GET /api/subscriptions` has a field `description` that spec 004 does not list. The group icon is derived from name and description. Without the field, the icon in the sidebar could differ from the icon of the same group in the discover view.
- Subscribing to a group that is subscribed already is answered with `204` also when the server has removed the group since. Spec 004 lists both "`204` also if already subscribed" and "`409` group removed".
- The cleanup deletes every article without a location, not only those that were in the group. This is what the data model says, and it includes stubs. It reads the whole table `article` once per unsubscribe.
- Unsubscribing leaves `last_synced_at` and `last_sync_error` of the group as they are. Step 7 decides what a new subscription does with them.
- The group view exists only for subscribed groups, and takes its data from the subscription list. No endpoint returns one group, and the threads endpoint of spec 008 answers `404` for a group that is not subscribed. For another group ID the view says so and links to the discover view. So the toggle in the group header can only unsubscribe.
- The badge "Subscribed" in the discover rows is gone; the filled star says the same.
- Frontend: on subscribe, the toggle changes before the backend has answered, and goes back with a message when the backend refuses. The sidebar shows the group after the answer.
- Frontend: a subscribed group that the server has removed gets the badge "Removed from the server" in the group header, next to the icon in the sidebar.
- Frontend: after unsubscribing, a short message confirms it.
- New TestBrowser method `put(path)` without a body.

Found by the owner in the review:

- On a wide screen the subscribe toggle was far from the group name, and the buttons of the group header far from the header. Decided: the discover view and the group view show their content in one centered column of at most 768 px, with the same width whether the sidebar is collapsed or not. The UI concept says so now. Whether 768 px suits article text is judged again when articles are shown (steps 10 and 11).
- The line above the subscriptions in the sidebar reached past the edge of the sidebar. It keeps its side margins now.

Verified and not verified:

- `./mvnw verify` passes: 282 backend tests and 109 frontend tests.- Tried by hand in a browser with the built jar against a small local server without TLS: subscribing in the discover view, the sidebar, the group view, "Mark all as read", and unsubscribing with its confirmation. The articles for the unread count were put into the database by hand.
- Not tried: the sidebar with the status icons for a running synchronization and a removed group (only in tests), a collapsed sidebar, and a real server.

Left for later steps:

- Hand a newly subscribed group to the synchronization, and cancel a running one on unsubscribe (step 7).
- Add the remaining columns of `article` (step 7 and later, as their specs say).
- Recompute threads in the unsubscribe cleanup (step 9); keep posted articles that wait for their location (step 13).

**Step 5, newsgroup discovery (2026-11-20)**

What was built:

- NNTP adapter: `LIST ACTIVE` and `LIST NEWSGROUPS`, both read line by line while the answer arrives, and `MODE READER`.
- Backend: the table `newsgroup` with all columns of the data model, the refresh job, the three endpoints of spec 003 below `/api/servers/{id}/groups`, the search, and the schedule of ADR 0020.
- Frontend: the group icon function with the tables of Group Icons, and the discover view with search, paging, the refresh button, the progress, the time of the last refresh and the error of the last failed one.
- Removing the server now deletes its groups. Other modules do this through `ServerRemovalCleanup` of the server module, in the transaction that removes the server.

Decided by the owner:

- Group Icons contradicted itself. The rule says that topic segments are tried from the last to the first, and `programmer` was a keyword of `code-xml`. The example says that `comp.lang.java.programmer` gets `coffee` through `java`. With the rule, it got `code-xml`. Decided: `programmer` is no longer a keyword and is in the list of ignored segments. The example stays.

Found by the owner while testing with a real server:

- The discover view stayed empty until the refresh button was pressed. The server existed before this step, so nothing had fetched its list yet. The view now fetches a list that was never fetched when it is opened.
- The group icon in a discover row was small next to two lines of text. It is 40 px now; Group Icons says so.
- The log showed an error with two stack traces when the browser closed the connection while a file of the frontend was sent. The error handler now recognizes this case, writes no answer and logs one line at `DEBUG`.
- The descriptions showed the group name. See the finding on descriptions below.
- The search has no index of its own. It reads all groups of the server for every request, once for the page and once for the count. Measured by the owner in the browser against a real server with 111,526 groups: about 90 ms per search. Decided: good enough for now. If it gets too slow, the first step is to count only when the search text changes, the second a full-text index in a new migration.

Decisions made during the step, open to a veto:

- `MODE READER` (left open in step 2): it is sent once per connection, before the first reading command, unless the capability list names `READER`. `200` and `201` confirm it; a server that does not know the command is read anyway; `502` and `480` fail the action. The connection test does not send it, so its commands are unchanged.
- `LIST NEWSGROUPS` is left out only when the capability list has a `LIST` line that does not name `NEWSGROUPS`. A list without a `LIST` line may be incomplete (finding of step 4), and a server without a capability list is asked as well. Spec 003 says "unless `CAPABILITIES` shows the server does not support it".
- The interval of the scheduled refresh is fixed at 24 hours, the default of `sync.group_list_interval_hours`. The setting itself and the table `app_setting` come with the settings section "Synchronization" in step 7.
- The schedule looks once a minute for group lists that are due. After a restart, the time of a failed refresh is not known any more, so a list that is due is fetched at the first look.
- The schedule leaves a server alone while its credentials are invalid. Every attempt would be another rejected login.
- Every failed refresh is followed by a scheduled attempt one hour later, also a failed manual one. ADR 0020 names the failed scheduled refresh.
- Response `480` (login required) to a reading command is reported as "the server refused", with the message "The server requires a login". It does not mark the credentials as invalid; that stays reserved for a rejected login.
- The search text may have at most 200 characters; a longer one is answered with `400`. Spec 003 has no limit.
- `page` below 0 and `size` outside 1 to 100 are answered with `400`, with field and rule. Spec 003 lists only `200` and `404` for this endpoint.
- `groupsReceived` in the refresh status keeps the number of the last refresh after it has ended, and is 0 after a restart.
- `subscribed` is `false` for every group until step 6.
- A group that the server lists twice is stored once. A description without text does not replace a stored description. A description for a group that is not in the group list is ignored.
- Group names are decoded like descriptions: UTF-8, otherwise ISO-8859-1.
- New protocol anomaly kinds: `list.malformed-active-line`, `list.malformed-newsgroups-line` and `list.malformed-description`.
- The property `wardroom.group-list.automatic-refresh` switches off the refresh for a new server and the schedule. It exists for the tests of other features, which would otherwise find unexpected connections on their mock servers. It is not documented for people who run Wardroom.
- Frontend: search text and page are part of the URL (`/discover?q=java&page=2`), so the back button and bookmarks work. The search starts 300 ms after the last key press.
- Frontend: a group shows the badge "Moderated" or "No posting"; a group that allows posting shows none. The subscribe toggle comes with step 6.
- Frontend, asked for by the owner in the review: when the discover view is opened and the group list was never fetched, the view starts the fetch itself. It does not do so after a failed fetch or while the credentials are invalid; then the owner presses the button. Before, a server that existed already had no list until the schedule looked, one minute after the start.
- Frontend: while a refresh runs, the status is asked for once a second. The list on the page is loaded again when the refresh ends, not while it runs.
- Group icons: a name that consists of a country hierarchy alone, such as `de`, keeps it as its hierarchy.

Findings:

- A transaction that read first and wrote later failed when another request had written in between (`SQLITE_BUSY_SNAPSHOT`). SQLite does not wait in that case. The test with 100,000 groups found it: marking removed groups collided with the session update of a status request. Every transaction now takes the write lock when it begins and waits for it up to the busy timeout. This also covers adding a server, which reads before it writes.
- A reader of the NNTP library ends quietly when the connection closes in the middle of a multi-line answer. A list cut short would look complete, and groups would be marked as removed. The adapter reads the lines itself and reports a lost connection.
- A real server sent no real description for any of its groups. For most groups the description was the group name; for about 1,900 groups of the classic hierarchies it was the rest of a `LIST ACTIVE` line, such as `0000015957 0000000001 y`. Decided by the owner: both count as no description, and the second is logged as a protocol anomaly. Spec 003 says so now. The raw lines were not kept, so that the server sent them this way is concluded from the stored data, not seen on the connection.
- Lucide has renamed four icons of the keyword table: `circle-help` to `circle-question-mark`, `laugh` to `face-grinning`, `music` to `eighth-notes`, `waves` to `waves-horizontal`. The table in Group Icons has the new names.
- The server service and the cleanup of the groups module need each other, so the service looks the cleanups up when a server is removed, not when it is created.
- New frontend component from shadcn/ui: `badge`.

Left for later steps:

- The subscribe toggle in the discover rows, and `subscribed` in the group list (step 6).
- The setting `sync.group_list_interval_hours` and its field in the settings section "Synchronization" (step 7).
- The quick switcher searches the discover view (step 16).

Not verified:

- By hand, the feature was tried with the built jar against a small local server without TLS: 8,400 groups, a list that breaks off, and a list with half of the groups. The refresh against a real server was tried by the owner, not by the author of the code.
- The contrast of the twelve circle colors against the white symbol was calculated from the color values, not measured in a browser. The lowest value is about 3.2:1.
- The image build.

**Step 4, server configuration and first-run wizard (2026-11-20)**

What was built:

- Backend: the five endpoints of spec 002 below `/api/servers`, the field rules, the connection test, and the removal. The table `nntp_server` has all columns of the data model; the ones for the group list and the posting identity are filled by later steps.
- Credential encryption: the key file in the data directory, `WARDROOM_ENCRYPTION_KEY_FILE` (ADR 0025), AES-256-GCM with the versioned format, and every row of the table "Missing or unusable key".
- Creating the account deletes the NNTP username and password and sets the authentication to "none" (ADR 0017). The server module does this through `AccountCreationCleanup`.
- Frontend: wizard step 2, shown at every URL while no server exists; the settings section "Server" with the connection test, the save warning and the removal with its confirmation; a notice on every view while the credentials are invalid.
- The logo in the sidebar is 50 % wider than before (requested by the owner). The collapsed sidebar keeps the small logo, because its column is too narrow for a bigger one.
- Other modules get two things from the server module: `NntpServers`, which returns the settings for the NNTP adapter and marks credentials as invalid, and the event `ServerCreated`.

Decisions made during the step, open to a veto:

- `CONNECTION_LOST` in the connection test (left open in step 2): a server that closes the connection during the test is reported as `SERVER_REFUSED`. Spec 002 keeps its seven failure types. The other choice is an eighth type in the spec.
- The connection test result has a third field, `postingAllowed`. Spec 002 requires that the result reports this but shows no field for it. Posting is offered when the capability list names `POST`. A list that names `READER` but not `POST` means no posting. In every other case the greeting decides (`200` or `201`).
- The connection test of the form answers `404` when the given `id` does not exist. Spec 002 lists only `200` and `400` for this endpoint.
- Where the password is decrypted. Spec 002 says only the NNTP adapter decrypts. Since step 2 the adapter takes the password in clear text in its settings. The server module therefore decrypts at the moment it builds these settings, and nobody keeps them. Following the spec literally would move the key into the NNTP module.
- Credentials are marked as invalid in three cases: the server answers `481` to the test that runs with saving; another module reports a `481` through `NntpServers`; the stored password cannot be decrypted. The mark is cleared when new credentials are saved, and when a test that runs with saving logs in successfully. The test of the form alone, without saving, never changes the mark.
- After the key file was lost, the encrypted password stays in the database, although it cannot be read. The owner enters a new one, which replaces it.
- Username and password of the server must not contain control characters. A line break in them would start a new NNTP command. Spec 002 only has the length rule.
- Host: letters, digits, dot, hyphen, underscore and colon. This covers host names, IPv4 and IPv6 addresses. Other characters are refused.
- The username of the server is stored as entered, without trimming. Name and host are trimmed.
- The group list fetch of step 5 starts in answer to the event `ServerCreated`. The event is published inside the transaction that stores the server.
- Two requests at the same time cannot both add a server; they are handled one after the other.
- In the frontend, "TLS mode" and "Authentication" are plain select fields, because each has two values.
- The mistakes in username and password of the form are shown once the other fields are valid, not together with them.
- After the server was removed, the server form is shown with the step indicator of the wizard, like at the first run.

Findings:

- On the development machine, a connection test with `localhost:119` was answered with `400` although nothing of Wardroom listens there. The security software of the machine handles that port. This does not affect Wardroom.
- Found by the owner with a real server: its capability list names only `VERSION 1` and `AUTHINFO USER PASS`, although it offers reading and posting. A capability list can therefore be incomplete. The posting rule above was changed for this. Step 5 must not rely on the list either when it decides about `MODE READER`, `LIST` and `OVER`.
- A toast stays as long as the mouse is over it. In a narrow window it covered the "Remove server" button at the right edge, so the button is now next to the other two.

Left for later steps:

- Removal deletes the row in `nntp_server`. Groups, subscriptions, article locations, articles and read state do not exist yet; the steps that create these tables (5, 6 and 7) add their deletion and the tests "removal deletes all server data" and "editing host or port keeps subscriptions and cache". For now a test shows that editing keeps the row with its other columns.
- The group list fetch itself (step 5). A test shows that adding a server publishes `ServerCreated` and editing does not.
- Marking credentials as invalid after `481` during synchronization (step 7) calls `NntpServers.markCredentialsInvalid`.
- Documentation of spec 002 for the user guide (step 16).

Not verified:

- A connection test against a server with TLS and a login was not tried by hand, only in the integration tests with the mock server. By hand, the test was tried against a small local server without TLS, and against a closed port.
- The permissions `0600` of the key file. The development machine runs Windows, where the test for them is skipped; it runs in CI on Linux.
- The image build.

**Step 3, authentication and application shell (2026-11-19)**

What was built:

- Backend: the six endpoints of spec 001 below `/api/auth`, the access rules, sessions in the database, login throttling, and the account creation with its cleanup. Spring Session JDBC works with SQLite; no session store of Wardroom's own was needed.
- Frontend: login page, wizard step 1 (account), the shell with the sidebar, the settings page with its section navigation, the "Account" section with the password change, log out, toasts and skeletons. The logo and the favicon are in place.
- A module that stores sensitive data implements `AccountCreationCleanup` of the `auth` module. Its cleanup runs in the transaction that creates the account (ADR 0017).

Decisions made during the step, open to a veto:

- Username: surrounding whitespace is removed at setup and at login. Apart from that, the username is stored as entered and compared exactly, including upper and lower case. Spec 001 says "stored as entered" and "after trimming"; this is how both were read.
- Lengths of username and password are counted in characters, not in bytes. An emoji is one character.
- Changed in the review: a session ends after 7 days of inactivity instead of 30, and the login page has the option "Remember me". Without it the session cookie ends with the browser session; with it the browser keeps the cookie and the server enforces the 7 days. Before, there was no such option and every cookie was kept for 400 days. Spec 001 was changed accordingly.
- Also decided in the review, for step 16: a session without "Remember me" will end after 30 minutes of inactivity. Until then it ends after 7 days like every session.
- A login always gets a new session; a session the browser had before is ended.
- Logins are checked one after the other, so parallel attempts cannot get around the throttling.
- A password that is too long to be valid is answered like a wrong password (`401`), not with `400`.
- A wrong current password at the password change is not throttled. Spec 001 only throttles the login.
- Changed in the review: a password change ends all sessions, including the current one, and the owner logs in again. Before, the current session stayed logged in, and a copy of its session ID in other hands would have stayed valid. Spec 001 was changed accordingly.
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
- Installation documentation of spec 001 (step 16): complete setup immediately; an update may require logging in again.

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
