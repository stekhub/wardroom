# 0003. Persistence: SQLite, jOOQ and Flyway

- Status: Accepted
- Date: 2026-11-18

## Context

Wardroom stores its data in SQLite, with schema managed by Flyway (brief §6, §8). The access layer has to handle two different workloads:

- **Domain data**: servers, posting identities, subscriptions, read state. Small volumes, ordinary CRUD.
- **Bulk ingestion and aggregate queries**: group discovery can return 100,000+ groups; each subscription imports hundreds of overview records; thread cards need counts and latest-activity dates grouped by thread, joined to the root article.

Options considered:

| Option | Assessment |
|---|---|
| JPA/Hibernate | SQLite has no sequences, so IDs use `IDENTITY`, which disables JDBC batch inserts in Hibernate. SQLite dialect is community-maintained. Bulk paths would need a separate JDBC route. |
| Spring Data JDBC | No SQLite dialect shipped; would need a custom one. Aggregate queries would still be hand-written strings. |
| Spring `JdbcClient` | Simple, no code generation, but no compile-time checking of SQL against the schema. |
| **jOOQ** | Open-source edition supports SQLite. Type-safe SQL generated from the schema; full SQL support including upserts, CTEs and window functions. |

## Decision

- **SQLite** as the only database.
- **Flyway** manages the schema (rules in [ADR 0004](0004-database-upgrades.md)).
- **jOOQ** (open-source edition) for all database access, behind repository classes.
- jOOQ code is generated at build time: Maven applies the Flyway migrations to a temporary SQLite file, and the jOOQ code generator reads the resulting schema. Generated code is not committed.
- Bulk ingestion uses batched `INSERT … ON CONFLICT` upserts.

**SQLite configuration**

- `PRAGMA foreign_keys = ON` on every connection (SQLite does not enforce foreign keys by default).
- WAL journal mode.
- A `busy_timeout`, so concurrent access waits instead of failing immediately.
- A small connection pool; writes are effectively serialized, because SQLite allows one writer at a time.
- Timestamp storage format is defined once, during data modelling, and used consistently.

## Consequences

- Query code that does not match the schema fails to compile.
- Repositories map jOOQ records to domain objects explicitly; there is no automatic entity mapping, lazy loading or dirty checking.
- Simple CRUD needs slightly more code than with Spring Data repositories.
- A fresh checkout must be built once (`mvnw compile`) before generated classes exist in the IDE.
- Contributors need to learn the jOOQ API; SQL knowledge carries over directly.
