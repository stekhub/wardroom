# 0019. Initial synchronization depth as an instance setting only

- Status: Accepted
- Date: 2026-11-18
- Supersedes: the per-subscription override of the initial synchronization depth in [ADR 0013](0013-synchronization-and-caching.md)

## Context

ADR 0013 lets the number of overviews fetched by a subscription's first synchronization (default 500) be overridden per subscription. The [UI concept](../../product/ui-concept.md) subscribes with one click and no dialog, so there is no place to enter a per-subscription value. Older articles can be loaded later with "load older discussions" anyway.

## Decision

- The initial synchronization depth is only an instance setting (`sync.initial_depth`, default 500), editable on the settings page.
- There is no per-subscription override. `subscription.initial_sync_depth` is removed from the data model.
- Changing the setting affects only subscriptions made afterwards.

## Consequences

- Subscribing needs no input beyond the click.
- A per-subscription value can be added later as a nullable column, if needed.
