# Open Questions

Questions raised during refinement that are not yet decided. Each is resolved in an ADR or a feature specification, then removed from this list.

## Open

None at the moment.

## Deferred beyond the MVP

Not part of the MVP; picked up on demand. Shorter sessions and two-factor authentication moved to step 18 of the [implementation plan](implementation-plan.md) on 2026-11-23; the dark theme, next unread, mark as unread, the mobile layout, the accessibility pass and full-text search (which also covers searching within a group) to steps 19 to 22; the notifications to step 26, the classic view, bookmarks (which also cover keeping articles) and the newest articles to steps 27 to 29, after 1.0. Filtering binary groups in the group list moved to step 21 on 2026-11-24.

- **Same group on several servers** (once multiple servers are supported). Direction: one merged view per group, with articles combined by `Message-ID` and shared read state, so a second server fills gaps of the first.
- **Quick switcher:** `Ctrl+K` / `⌘K` opens a search over the subscribed groups and the groups of the server, to open a group without the mouse.
- **Date range of a group:** the group shows the dates of its earliest and its latest article on the server. To be decided then: where it is shown, and when the dates are asked for, because the group list of a server carries article numbers but no dates.
- **Showing only chosen authors:** in a group full of noise, the owner shows only the authors they trust and hides everyone else, the other way round from ignoring ([spec 010](specs/010-ignoring/spec.md)). To be decided then: whether the list of trusted authors is per group or for all groups, and how a new author is added from an article.
- **Scope additions:** killfile patterns and scoring beyond [spec 010](specs/010-ignoring/spec.md), cancelling or superseding own posts.
- **Text of HTML-only messages:** an article without a plain-text part shows only the notice "HTML message, not displayed" ([ADR 0014](architecture/decisions/0014-plain-text-article-display.md)). Direction: show the text taken out of the HTML, with tags removed, paragraphs and line breaks kept and link targets written out, rendered as escaped text like every other article, with a notice that it was converted. Displaying the HTML itself, also after a confirmation, is not planned. This needs a new ADR, because it changes ADR 0014.
- **Removing whole old threads** when header data alone exceeds the cache size limit ([ADR 0023](architecture/decisions/0023-cache-size-limit.md)).
- **Archive download:** the owner downloads the full text of many articles at once, for example a whole group or a range of years, to keep them for archiving. Archived articles are kept when the cache is cleaned and can be found by the full-text search. To be decided then: how the download is limited, paused and resumed, and how it shares the connections with the synchronization. This needs a new ADR, because it changes the rule that content is fetched only when an article is opened ([ADR 0013](architecture/decisions/0013-synchronization-and-caching.md)) and the cache size limit ([ADR 0023](architecture/decisions/0023-cache-size-limit.md)).
- **Import from archive dumps:** the owner imports articles from Usenet archives published by the Internet Archive, to read groups from years that no server keeps any more. To be decided then: which file formats are read, how imported articles are stored without a location on a server, and how duplicates and broken headers are handled.
- **Microsoft Store:** the Windows app of step 24 also in the Microsoft Store, for people who look for apps there and for automatic updates. To be decided then: a window with an embedded WebView2 instead of Edge in app mode, because the Store expects more than a program that opens a browser; own license terms with the AGPL instead of the standard terms of the Store; and how the rules of the Store for content from other people apply to a newsreader.
- **NNTP password from a file:** the owner passes the NNTP password itself to the container as a file, for example a Docker secret, and Wardroom stores no password in the database. This would remove the stored secret instead of protecting its key ([ADR 0025](architecture/decisions/0025-external-encryption-key.md)). To be decided then: how the settings page shows a password it cannot change, and what happens when the file changes.
