# Open Questions

Questions raised during refinement that are not yet decided. Each is resolved in an ADR or a feature specification, then removed from this list.

## Open

None at the moment.

## Deferred beyond the MVP

Not part of the MVP; picked up on demand.

- **Same group on several servers** (once multiple servers are supported). Direction: one merged view per group, with articles combined by `Message-ID` and shared read state, so a second server fills gaps of the first.
- **Reading aids:** "next unread" with a keyboard key (`N`), across threads in the current sort order, ending with a link to the next group with unread articles; mark as unread; search within a group by subject or author; keeping articles (a star that also exempts them from the cache size limit).
- **Bookmarking threads and articles:** the owner marks a thread or a single article to find it again, and a view lists the marked ones, newest mark first. A bookmarked article is kept when the cache is cleaned. To be decided then: whether a bookmark is the same as "keeping articles" under reading aids.
- **Newest articles overview:** one page with the newest articles of all subscribed groups.
- **Push notifications on a mobile phone**, for example for a reply to an own article. To be decided then: which events notify, and how the notification reaches the phone.
- **Classic view:** a second way to show a group, as newsreaders traditionally do: a compact list of threads as a tree of subjects, with the selected article below or beside it. The owner switches between this view and the card view.
- **Two-factor authentication** for the login of the owner. To be decided then: which second factor, and how the owner gets back in when the second factor is lost.
- **Date range of a group:** the group shows the dates of its earliest and its latest article on the server. To be decided then: where it is shown, and when the dates are asked for, because the group list of a server carries article numbers but no dates.
- **Full-text search:** searching the cached articles of the subscribed groups by words in the subject, the author and the text, in one group or in all of them. Results are shown as articles with their thread, newest first. Only cached text can be found; an article whose text was never opened is found by its subject and author only. Direction: a full-text index of SQLite (FTS5), kept up to date when content is cached or cleaned.
- **Scope additions:** killfile patterns and scoring beyond [spec 010](specs/010-ignoring/spec.md), filtering binary groups in the group list, mobile layout, dark theme, cancelling or superseding own posts.
- **Text of HTML-only messages:** an article without a plain-text part shows only the notice "HTML message, not displayed" ([ADR 0014](architecture/decisions/0014-plain-text-article-display.md)). Direction: show the text taken out of the HTML, with tags removed, paragraphs and line breaks kept and link targets written out, rendered as escaped text like every other article, with a notice that it was converted. Displaying the HTML itself, also after a confirmation, is not planned. This needs a new ADR, because it changes ADR 0014.
- **Removing whole old threads** when header data alone exceeds the cache size limit ([ADR 0023](architecture/decisions/0023-cache-size-limit.md)).
- **NNTP password from a file:** the owner passes the NNTP password itself to the container as a file, for example a Docker secret, and Wardroom stores no password in the database. This would remove the stored secret instead of protecting its key ([ADR 0025](architecture/decisions/0025-external-encryption-key.md)). To be decided then: how the settings page shows a password it cannot change, and what happens when the file changes.
