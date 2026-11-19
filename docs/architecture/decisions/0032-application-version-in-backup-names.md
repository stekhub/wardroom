# 0032. Application version in backup names

- Status: Accepted
- Date: 2026-11-20
- Extends: [ADR 0004](0004-database-upgrades.md); supersedes its backup file name

## Context

Before pending migrations are applied, the database is backed up ([ADR 0004](0004-database-upgrades.md)). The file name contains the schema version and a timestamp, for example `wardroom-v1-20261119T223230421Z.db`.

Going back to an earlier version of Wardroom needs two things that match: the earlier image and the backup of the data as that version left it. The schema version in the file name does not tell the owner which image that is.

At the moment of the backup the new version is running. It knows its own version, but the database does not record which version used it last.

## Decision

**Wardroom remembers the version that used the database**

- At every start, after the migrations, Wardroom stores its own version in the database, in `app_setting` under the key `app.version`. The value is not shown on the settings page and cannot be edited.

**The backup is named after that version**

- Before pending migrations are applied, Wardroom reads the stored version and uses it in the backup's file name: `wardroom-{application version}-schema{schema version}-{timestamp}.db`, for example `wardroom-0.3.1-schema7-20270115T093000123Z.db`.
- The name says: this is the data as version 0.3.1 left it.
- The stored version is read with plain SQL, before the migrations, as the migration rules of ADR 0004 require.
- If the database has no stored version, the file name contains `unknown` in its place. This applies to a database that was last used by a version from before this decision.

**Log**

- The backup is logged in one line that names both versions: the version the data comes from and the version that is starting.

**Documentation**

- The user guide gets a section on going back to an earlier version: stop the container, set the image tag to the earlier version, copy the matching backup over the database file, start the container.

**Unchanged**

- When a backup is written, how many backups are kept and what happens when it cannot be written ([ADR 0028](0028-backup-retention.md)).
- The downgrade protection of ADR 0004.

## Consequences

- The owner can see from the file name which image a backup belongs to.
- `app_setting` holds one value that is not a setting of the owner. This avoids a table of its own for a single value.
- The decision is implemented before the first release, so no released version writes backups with the old name and the `unknown` case does not occur in practice.
