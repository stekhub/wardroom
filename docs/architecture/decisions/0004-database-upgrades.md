# 0004. Database upgrades and migration rules

- Status: Accepted; backup retention in [0028](0028-backup-retention.md); backup file name superseded by [0032](0032-application-version-in-backup-names.md)
- Date: 2026-11-18

## Context

Users run Wardroom on their own hosts and upgrade by pulling a new image. Their database may be several schema versions behind. Upgrades must never silently lose data (brief §8). jOOQ code is generated from the latest schema only ([ADR 0003](0003-persistence.md)), so the application must never run against an outdated schema.

## Decision

**Startup sequence**

1. Flyway runs before any repository code accesses the database. A test verifies this ordering.
2. If migrations are pending, the database is first backed up with `VACUUM INTO` to `/config/backups/`, with the schema version and a timestamp in the file name.
3. Pending migrations are applied.
4. If a migration fails, startup aborts. The backup remains available for restore.

**Downgrade protection**

Flyway is configured to refuse startup when the database contains migrations unknown to the running version (for example, an older image started against a newer database). The documented recovery is restoring the matching backup.

**Migration rules**

- Migrations are plain SQL.
- If a Java-based migration is ever required, it must use plain SQL and must not use jOOQ generated classes, because those reflect the latest schema, not the schema at that migration's point in history.
- Released migrations are never modified. Flyway checksum validation stays enabled.
- Changes SQLite cannot perform with `ALTER TABLE` (changing a column type or constraint) use the table-rebuild pattern: create the new table, copy the data, drop the old table, rename. `PRAGMA defer_foreign_keys = ON` is used inside the migration, because `PRAGMA foreign_keys` cannot be changed inside the transaction Flyway runs each migration in.
- During early development, before the first release, schema resets are acceptable.

**Upgrade tests**

For each released schema version, a test migrates an empty database to that version, inserts representative data, migrates to the latest version and verifies the data. These tests run in CI.

## Consequences

- An upgrade either completes or leaves the database unchanged with a backup next to it.
- Old application versions cannot damage a newer database.
- Backups accumulate in `/config/backups/`; a retention rule is defined in the relevant specification.
