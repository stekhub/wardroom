# 0021. Jump to date and multiple synchronized ranges

- Status: Accepted; read state of fetched articles superseded by [0022](0022-older-articles-arrive-read.md); direction of the jump and "load more" superseded by [0033](0033-date-view-up-to-a-day.md)
- Date: 2026-11-18
- Supersedes: the single contiguous synchronized range of a group in [ADR 0013](0013-synchronization-and-caching.md) and the [data model](../data-model.md)

## Context

Providers keep text articles for many years. Reading a group as it was at a given time ("digital archaeology") should not require fetching every article between today and then.

NNTP has no command to fetch articles by posting date. `NEWNEWS` lists articles that arrived since a time, with no end, and many providers disable it. Within a group, however, article numbers are assigned in order of arrival, so they can serve as a time axis.

ADR 0013 keeps one contiguous synchronized range per group. Jumping to an old date creates a gap between the old articles and the newest ones.

## Decision

**Jump to date**

- The group view offers a date picker. Choosing a date shows the group from that date forward.
- Wardroom finds the first article number on or after the date by binary search between the server's low and high watermark: it fetches the overview of a few numbers around the midpoint, reads their `Date`, and halves the range. About 20–30 small requests suffice even for millions of articles.
- From that number, the next 500 overviews (the instance's initial depth) are fetched and shown oldest first. "Load more" fetches the next 500.
- If the date is older than the oldest article the server still has, the view starts at the oldest available article and says from when it is.
- The result is approximate, because `Date` is set by the poster and can be wrong. Arrival order is reliable, so a wrong date shifts the starting point only slightly.

**Several synchronized ranges per group**

- A group has any number of synchronized ranges of article numbers. Ranges never overlap; ranges that touch or overlap are merged.
- Background synchronization extends the newest range up to the server's high watermark.
- "Load older discussions" extends the range being viewed downwards; "load more" in a date view extends it upwards. Each step fetches up to 500 overviews and stops at the neighboring range, merging with it.
- A jump to a number already inside a range fetches nothing.

## Consequences

- Old periods of a group can be read without fetching everything in between.
- The data model replaces `newsgroup.oldest_synced_number` and `newest_synced_number` with a `synced_range` table.
- Articles fetched by a jump are ordinary cached articles: they appear in threads, count as unread until read, and are deleted on unsubscribe.
- Threads of old periods may be incomplete at the edges of a range until neighboring articles are loaded.
- Jumping requires the server to be reachable.
