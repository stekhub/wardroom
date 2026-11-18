# 0028. Backup retention

- Status: Accepted
- Date: 2026-11-18
- Extends: [ADR 0004](0004-database-upgrades.md)

## Context

Before pending migrations are applied, the database is copied to `/config/backups/` (ADR 0004). ADR 0004 leaves the retention rule open. Each backup is as large as the database, up to the cache size limit ([ADR 0023](0023-cache-size-limit.md)), and backups do not count toward that limit. Without a rule they accumulate with every update that changes the schema.

## Decision

- Wardroom keeps the **2 newest** backups.
- Older backups are deleted after the pending migrations were applied successfully, never before.
- If the backup cannot be written, for example because the disk is full, startup aborts with an error naming the cause. No migration is applied.

## Consequences

- Backups use at most twice the database size.
- The owner can go back two schema updates with Wardroom's own backups; anything older needs the owner's own backups of `/config`.
- The number is fixed, not a setting.
