# Open Questions

Questions raised during refinement that are not yet decided. Each is resolved in an ADR or a feature specification, then removed from this list.

## Open

None at the moment.

## Deferred beyond the MVP

Not part of the MVP; picked up on demand.

- **Same group on several servers** (once multiple servers are supported). Direction: one merged view per group, with articles combined by `Message-ID` and shared read state, so a second server fills gaps of the first.
- **Reading aids:** "next unread" with a keyboard key (`N`), across threads in the current sort order, ending with a link to the next group with unread articles; mark as unread; search within a group by subject or author; keeping articles (a star that also exempts them from the cache size limit).
- **Scope additions:** killfile patterns and scoring beyond [spec 010](specs/010-ignoring/spec.md), filtering binary groups in the group list, full-text search of cached articles, mobile layout, dark theme, cancelling or superseding own posts.
- **Removing whole old threads** when header data alone exceeds the cache size limit ([ADR 0023](architecture/decisions/0023-cache-size-limit.md)).
