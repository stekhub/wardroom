# 0022. Older articles arrive read

- Status: Accepted
- Date: 2026-11-18
- Supersedes: "count as unread until read" for articles fetched by a jump in [ADR 0021](0021-jump-to-date.md)

## Context

ADR 0021 treats articles fetched by jump to date as ordinary cached articles that count as unread. The same applies to "load older discussions" and "load more". A single jump or step adds up to 500 articles to the group's unread count, although the owner went looking for history, not for new activity. Unread markers are meant to show what is new; on browsed history they are noise.

## Decision

- Articles that are newly stored by "load older discussions", by "load more" in a date view, or by a jump to date are marked read when they are stored: a `read_state` row is inserted in the same transaction.
- This also applies to a stub that one of these steps turns into a normal article.
- Articles that were already stored keep their read state.
- Articles stored by background synchronization, by the refresh button and by the first synchronization (including after renumbering) are unread, as before.

## Consequences

- The unread count and the "new" markers reflect only articles that arrived at the newest end of a group.
- No schema change: read state is the existing `read_state` row.
- A reply to an old thread that arrives through background synchronization is unread as usual, so new activity in old threads is still visible.
- Unsubscribing deletes this read state with the articles, as before.
