# 0034. The cache size limit can be switched off

- Status: Accepted
- Date: 2026-11-22
- Supersedes: the part of [ADR 0023](0023-cache-size-limit.md) that says the limit always applies

## Context

ADR 0023 limits the size of the database with the setting `cache.max_size_gb`. The limit always applies; its highest value is 1000 GB. An owner with enough disk space may want to keep everything that was fetched, for as long as the groups are subscribed. A very high limit says this only indirectly.

## Decision

- A second setting, `cache.limit_enabled`, says whether the limit applies. It is a checkbox in the synchronization section of the settings page: "Limit the cache size".
- The limit is on by default. An installation in which nothing was changed behaves as ADR 0023 says.
- While the limit is off:
  - the cleanup job does not run, and no cached content is deleted;
  - `cache.max_size_gb` keeps its value, and its field is not shown;
  - the section shows the current use alone, for example "1.2 GB used", and says that the cache grows for as long as groups are subscribed;
  - the warning that the limit cannot be reached is not shown.
- Switching the limit on runs the cleanup job at once, as lowering the limit does.
- A cleanup that is running when the limit is switched off finishes the batch it is in and then stops.
- Everything else of ADR 0023 stays: the measurement, the order of the cleanup, what is never deleted, and the batches.

## Consequences

- Without a limit, the database grows with every synchronization. The owner sees the current use in the settings and can switch the limit on again at any time.
- The backups before an update are as large as the database ([ADR 0028](0028-backup-retention.md)). Without a limit, they grow with it.
- The settings have one more value, and the settings endpoints one more field, `cacheLimitEnabled`.
