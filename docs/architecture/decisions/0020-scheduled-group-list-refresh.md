# 0020. Scheduled group list refresh

- Status: Accepted
- Date: 2026-11-18
- Supersedes: the group list trigger in [ADR 0013](0013-synchronization-and-caching.md) ("when the server is set up, then only on manual refresh")

## Context

ADR 0013 fetches the group list only when the server is set up and on manual refresh. New groups then never appear and removed groups are never marked unless the owner remembers to refresh. A full `LIST ACTIVE` on a large provider returns over 100,000 lines, roughly 5–10 MB: acceptable once a day, too much at the article synchronization interval.

`NEWGROUPS` (RFC 3977) lists only groups created since a given time. It does not detect removed groups and is not reliably supported, so it is not used.

## Decision

- The group list is fetched when the server is set up, on manual refresh, and on a schedule.
- The schedule is an instance setting, `sync.group_list_interval_hours`, default 24, range 1–168, editable on the settings page.
- The interval counts from the last successful refresh (`groups_refreshed_at`). A failed scheduled refresh is attempted again after one hour.
- Group list refresh and article synchronization are separate jobs with separate intervals.

## Consequences

- New and removed groups appear within a day without manual action.
- The daily refresh uses one connection for a few minutes on large providers.
