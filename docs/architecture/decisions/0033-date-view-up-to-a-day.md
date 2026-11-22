# 0033. The date view shows a group up to a day

- Status: Accepted
- Date: 2026-11-21
- Supersedes: the direction of the jump and of the date view, and "load more", in [ADR 0021](0021-jump-to-date.md)

## Context

ADR 0021 shows a group from the chosen date forward, oldest first, with "load more" towards newer articles. In use, this turned out to be the wrong way round:

- The normal group view is newest first, and going down the list goes into the past. The date view was the only place that read forward and had its own loading button.
- A chosen date is understood as "the group as it was then". In a quiet group, the view from that date forward begins with discussions that are years newer than the chosen date.

## Decision

- Choosing a date shows the discussions that began on or before that day, newest first. The chosen day is included.
- Wardroom searches for the last article before the end of the chosen day. The search is the binary search of ADR 0021; it goes on until fewer than 20 article numbers remain.
- From that article downwards, up to 500 overviews (the instance's initial depth) are fetched. The fetch stops at the neighboring range and merges with it.
- "Load older discussions" extends the range of the date view downwards, as in the normal view. "Load more" is removed; ranges are no longer extended upwards by the owner.
- If the chosen day is before the oldest article the server still has, nothing is fetched. The view says from when the oldest article is and offers that day.
- If the end of the chosen day lies inside a range, nothing is fetched.

## Consequences

- The date view and the normal view are read the same way and share "Load older discussions".
- To see what came after a day, the owner picks a later day.
- The gap between the range of a jump and the newest range is closed only by further jumps or by "Load older discussions" from above.
- Several ranges per group, their merging and the date search of ADR 0021 stay as they are.
- Articles fetched by a jump still arrive read ([ADR 0022](0022-older-articles-arrive-read.md)).
