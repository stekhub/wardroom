# Wardroom – Project Brief

> Status: initial briefing. This document provides the starting context and agreed direction.
> Details are refined step by step in the product, architecture and specification documents.
> Where decisions differ from this brief, the [architecture decision records](../architecture/decisions/README.md) take precedence.

## 1. Project Overview

**Wardroom** is a free, open-source, self-hosted NNTP client for reading and participating in Usenet discussions.

The goal is to build a modern discussion experience around traditional Usenet newsgroups, combining the openness and decentralization of Usenet with a user experience inspired by modern discussion platforms such as Reddit.

Wardroom is a client application, not a Usenet provider, hosting platform, content repository or indexer.

Users connect Wardroom to their own NNTP server.

### Product positioning

A modern, self-hosted NNTP client for Usenet and private news servers.

Core idea: Usenet doesn't need reinventing. It needs a better client.

## 2. Product Principles

- Free and open source.
- Self-hosted.
- Decentralized: users connect to their own NNTP providers.
- Text-based discussions.
- Modern, intuitive user experience.
- No central content infrastructure.
- No advertising or monetization requirements.
- Keep the architecture extensible without overengineering the MVP.

## 3. Functional Scope

### 3.1 NNTP connectivity

Wardroom must support connecting to user-configured NNTP servers.

Requirements:

- Support multiple NNTP server configurations.
- Support TLS connections.
- Support NNTP authentication using username/password.
- Support server capability discovery using `CAPABILITIES`.
- Support newsgroup discovery using `LIST ACTIVE`.
- Optionally retrieve descriptions using `LIST NEWSGROUPS` when supported.
- Handle server-specific group availability and article retention.
- Support reading and posting articles.

Relevant NNTP commands include:

- `CAPABILITIES`
- `LIST ACTIVE`
- `LIST NEWSGROUPS`
- `LIST OVERVIEW.FMT`
- `GROUP`
- `OVER`
- `ARTICLE`
- `HEAD`
- `BODY`
- `POST`
- `QUIT`

### 3.2 Multiple NNTP servers

Multiple NNTP servers are part of the intended product architecture.

Important distinction:

A newsgroup is a logical entity, but its availability and article numbers are specific to an NNTP server.

The same group may exist on several servers with different article ranges, retention and permissions.

Subscriptions must therefore be associated with a server-specific newsgroup, not just a global newsgroup name.

### 3.3 Newsgroup discovery and subscriptions

Users can:

- Browse available newsgroups on configured servers.
- Search for newsgroups.
- Subscribe to newsgroups.
- Unsubscribe from newsgroups.
- View their subscribed groups.
- Refresh group catalogues.

Subscriptions are server-specific.

Initial subscription synchronization should retrieve approximately the latest 500 article overviews.

Use `GROUP` to retrieve article range information and `OVER` to retrieve article metadata.

Do not initially download all article bodies.

### 3.4 Discussion experience

The primary user experience within a newsgroup should be inspired by Reddit.

A newsgroup should display discussion threads as individual cards rather than presenting a traditional email-style message inbox.

Each thread card should support displaying:

- Subject.
- Original author.
- Original posting date.
- Short text excerpt.
- Number of known messages in the thread.
- Latest activity timestamp.
- Unread/new activity indication.

Users should be able to:

- Open a thread.
- Read its messages.
- Navigate nested replies.
- Expand and collapse replies.
- Reply to the original post or an individual article.
- Sort discussions.

Initial sorting options:

- Newest.
- Latest activity.
- Most discussed.

Do not implement Reddit-style voting, karma or popularity algorithms.

The thread is the primary presentation unit.

### 3.5 Article and thread handling

NNTP article identity is based on `Message-ID`, not article number.

Article numbers are server- and group-specific and must not be treated as globally unique identifiers.

Use these headers to construct threads:

- `Message-ID`
- `References`
- `In-Reply-To`

`References` is the primary source for determining thread relationships.

Subject matching may be used as a fallback but must not be the primary threading mechanism.

Thread structures can initially be derived from locally stored article metadata.

The displayed message count represents locally known messages, not necessarily every message available on the server. Older messages may not have been synchronized.

### 3.6 Lazy article fetching

Article overview metadata and article bodies should be handled separately.

Expected behavior:

| Action | Behavior |
|---|---|
| Subscribe to group | Fetch latest ~500 overviews |
| Open group | Display locally stored overviews |
| Open thread | Display known thread structure |
| Open article | Fetch article body if not cached |
| Refresh group | Fetch new overviews |
| Load older discussions | Fetch older overview ranges |

Cache article bodies after retrieval.

### 3.7 Posting

Users must be able to create new discussions and reply to existing articles.

Posting scope:

- Plain-text messages.
- UTF-8 encoding.
- Unicode and emojis supported.
- No attachments.
- No inline images.
- No binary content.
- No HTML or rich-text editor.
- Optional automatic signature.
- Quoted replies.

Generate standard NNTP article headers, including:

- `From`
- `Newsgroups`
- `Subject`
- `Message-ID`
- `Date`
- `References` when replying
- `Content-Type: text/plain; charset=UTF-8`

Use NNTP `POST`.

Correctly implement NNTP CRLF line endings and dot-stuffing.

### 3.8 Text-only content policy

Wardroom is strictly a text discussion client.

It must not implement:

- Binary Usenet downloads.
- yEnc decoding.
- Multipart file assembly.
- NZB support.
- Attachment downloads.
- File-sharing functionality.
- Download-manager integration.

For incoming articles:

- Accept readable `text/plain` content.
- Support UTF-8 and common character sets.
- Safely handle MIME headers.
- Ignore or reject attachments and unsupported MIME parts.
- Do not automatically load remote images or resources.
- Never execute scripts or render unsafe HTML.

Emojis and Unicode characters are permitted.

## 4. Authentication and User Management

Wardroom requires its own authentication, separate from NNTP authentication.

Initial MVP:

- One Wardroom installation.
- One Wardroom user.
- One or more NNTP server configurations.
- Each server has its own NNTP credentials and posting identity.

Wardroom authentication:

- Local username/password.
- No email address required.
- Passwords hashed using Argon2id.
- Secure session cookies.
- CSRF protection.
- Login and logout.
- Account creation during initial setup.
- No public registration.
- No external identity provider.
- No roles or permissions.
- No multi-tenant user management.

Keep Wardroom identity separate from NNTP posting identity.

Posting identity belongs to an NNTP server configuration and includes display name, email address and optional signature.

## 5. Credential Storage

NNTP credentials must be retrievable by Wardroom to authenticate with external servers.

They cannot be stored as password hashes.

Agreed approach:

- Encrypt NNTP passwords before storing them in SQLite.
- Use AES-256-GCM.
- Generate a random encryption key automatically during initial setup.
- Store the key in a separate persistent configuration file.
- Do not ask users to provide an encryption key.
- Restrict filesystem permissions on the key file.
- Store ciphertext only in the database.
- Never expose credentials through APIs or application logs.
- Always use TLS for NNTP authentication.

The key file and database must be backed up together.

This provides protection if someone obtains only the database file, but does not protect against a complete compromise of the host or running application.

Version the encryption format to allow future algorithm changes and key rotation.

## 6. Architecture and Technology

Preferred initial technology direction:

- Backend: Kotlin + Spring Boot.
- Frontend: Angular.
- Database: SQLite.
- Deployment: Docker / Docker Compose.
- Testing: JUnit and appropriate integration testing.
- Database migrations: Flyway.

Start with a modular monolith.

Do not introduce microservices.

### Architectural principles

- Keep NNTP protocol implementation behind an adapter/interface.
- Keep domain logic independent of NNTP implementation details.
- Separate article metadata from article bodies.
- Separate logical newsgroups from server-specific newsgroup availability.
- Treat subscriptions as server-specific.
- Use `Message-ID` as the primary article identity.
- Design synchronization as independent background jobs.
- Keep database access behind repositories.

Potential NNTP abstraction:

```kotlin
interface NntpClient {
    fun connect()
    fun listActiveGroups(): Sequence<NewsgroupInfo>
    fun selectGroup(name: String): GroupInfo
    fun fetchOverview(
        group: String,
        from: Long,
        to: Long
    ): Sequence<ArticleOverview>
    fun fetchArticle(messageId: String): Article
    fun post(article: OutgoingArticle)
}
```

## 7. Initial Data Model

Suggested entities:

| Entity | Responsibility |
|---|---|
| `WardroomUser` | Local Wardroom account |
| `NntpServer` | NNTP connection configuration and credentials |
| `NntpPostingIdentity` | Posting identity associated with an NNTP server |
| `Newsgroup` | Logical newsgroup identity |
| `ServerNewsgroup` | Newsgroup availability and metadata on a particular server |
| `Subscription` | User subscription to a server-specific newsgroup |
| `Article` | Article metadata and optionally cached body |
| `ArticleReadState` | User-specific read/unread state |
| `SyncState` | Synchronization progress and status |

Important relationships:

```text
WardroomUser
    |
    +--- NntpServer
    |       |
    |       +--- NntpPostingIdentity
    |
    +--- Subscription
              |
              +--- ServerNewsgroup
                        |
                        +--- Newsgroup
                        |
                        +--- Article
```

The above is conceptual. Define actual cardinalities and foreign keys during detailed data modelling.

For example, enforce uniqueness on:

`(wardroom_user_id, server_newsgroup_id)`

Thread relationships can initially be derived from article headers rather than requiring a separate persisted Thread entity.

## 8. Database Lifecycle and Migrations

Use Flyway for schema management.

Migration scripts:

```text
src/main/resources/db/migration/
    V1__initial_schema.sql
    V2__add_nntp_servers.sql
    V3__add_server_newsgroups.sql
    V4__add_subscriptions.sql
```

At application startup:

- If the database does not exist, initialize it through migrations.
- If it exists, detect its migration version.
- Execute all pending migrations.
- Abort startup if migrations fail.
- Never silently discard user data.

Use a persistent Docker volume for database and configuration files.

Back up the database before applying migrations.

Never modify an already released migration; add new migrations instead.

During very early development, schema resets are acceptable. Once users depend on released versions, preserve data through migrations.

## 9. Synchronization

Separate newsgroup discovery from article synchronization.

Newsgroup discovery:

1. Connect to NNTP server.
2. Execute `LIST ACTIVE`.
3. Stream and parse results.
4. Batch upsert server-specific group metadata.
5. Retrieve descriptions with `LIST NEWSGROUPS` if supported.
6. Record synchronization status.

Article synchronization:

1. Select group using `GROUP`.
2. Retrieve current article range.
3. Fetch overview records using `OVER`.
4. Parse article metadata and threading headers.
5. Persist in batches.
6. Update synchronization state only after successful completion.

Do not delete groups just because they disappear from one synchronization result.

Retain unavailable groups and mark their status appropriately.

## 10. Deployment

Wardroom should be straightforward to deploy using Docker Compose.

Use persistent volumes for:

- SQLite database.
- Encryption key.
- Application configuration.

Example:

```yaml
services:
  wardroom:
    image: wardroom/wardroom:latest
    volumes:
      - wardroom-data:/data
      - wardroom-config:/config
    environment:
      SPRING_DATASOURCE_URL: jdbc:sqlite:/data/wardroom.db

volumes:
  wardroom-data:
  wardroom-config:
```

The encryption key must survive container recreation and application upgrades.

## 11. Explicitly Out of Scope

Do not implement these features unless the product scope is explicitly changed:

- Binary Usenet downloads.
- Attachments.
- NZB files.
- yEnc.
- File-sharing features.
- Central Usenet infrastructure.
- Central content indexing.
- Voting, karma or reputation systems.
- Advertising or monetization.
- HTML/rich-text composition.
- Public Wardroom user registration.
- Complex multi-tenant user management.

## 12. Development Approach

Use specification-driven development (SDD).

The coding agent should not immediately implement the entire application.

First establish:

1. Product requirements.
2. Domain model.
3. Architecture.
4. Architecture decision records (ADRs).
5. Feature specifications.
6. Implementation tasks.
7. Tests and acceptance criteria.

Suggested feature specification structure:

```text
docs/
  product/
    vision.md
    mvp-scope.md
  architecture/
    design.md
    decisions/
  specs/
    001-user-authentication/
    002-nntp-server-configuration/
    003-newsgroup-discovery/
    004-subscriptions/
    005-article-synchronization/
    006-threading/
    007-discussion-view/
    008-article-reading/
    009-article-posting/
    010-credential-storage/
```

Each feature specification should include:

- Functional requirements.
- User stories.
- Acceptance criteria.
- Technical design.
- Data model changes.
- API contracts.
- Error handling.
- Security considerations.
- Testing requirements.
- Implementation tasks.

## 13. Instructions to the Coding Agent

Treat this document as the initial product context and source of agreed direction.

Your first task is to help refine the product requirements and architecture, not to start implementing features.

Work iteratively.

Identify ambiguities, missing requirements and architectural decisions that need clarification.

Do not silently invent product behavior.

When making recommendations, explain trade-offs and distinguish between:

- Agreed decisions.
- Proposed decisions.
- Open questions.

Avoid overengineering the MVP, but do not introduce architectural shortcuts that make the intended multi-server architecture difficult to support later.

Prioritize a coherent domain model, clear boundaries, testability and maintainability.

The end result should be a set of concise, implementable specifications that can guide incremental development of Wardroom.
