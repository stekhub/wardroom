# 009. Article posting

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.7, §4, [UI concept](../../product/ui-concept.md), [spec 002](../002-nntp-server-configuration/spec.md), [spec 005](../005-article-synchronization/spec.md), [spec 006](../006-threading/spec.md), [spec 007](../007-article-reading/spec.md), [spec 008](../008-discussion-view/spec.md)

## Scope

The posting identity, writing new discussions and replies, quoting, the generated article, sending it with NNTP `POST`, and showing the owner's post before the server lists it. Cancelling or superseding own posts is not part of the MVP ([deferred](../../open-questions.md#deferred-beyond-the-mvp)).

## User stories

- As the owner, I read without ever setting up a posting identity; I am asked for one only when I first post.
- As the owner, I start a discussion in a group and reply to any article, quoting what I answer.
- As the owner, I write in any language, including emojis, and my post reaches other readers correctly.
- As the owner, I understand why a post was refused and what I can do about it.
- As the owner, I see my post in the thread right after sending it.

## Functional requirements

**Posting identity**

- Display name, email address and an optional signature, stored per server.
- Not part of the first-run wizard. When the owner opens an editor without an identity, the editor first asks for it inline; it is saved for the server and can be changed later in the settings section "Posting identity" (Lucide `id-card`).
- The email address must have the form `local@domain`; it is not verified. A hint explains that a disguised address can be used against spam.
- A signature of more than 4 lines shows a warning but is accepted.

**Writing**

- A new discussion is written on its own page, `/groups/{id}/new`, opened with "New discussion" (Lucide `square-pen`) in the group header.
- A reply is written in an editor opened inline below the article, with a "Reply" button on each article of the thread page. The button is available once the article's body is displayed.
- The editor is a plain text area. A reply's subject is prefilled with "Re: " and the parent's subject without existing reply prefixes ([spec 006](../006-threading/spec.md)); it can be edited.

**Quoting**

- A reply starts with an attribution line "{name} wrote:" and the parent's body with every line prefixed by `> `; the parent's signature is left out.
- If text in the parent article is selected when "Reply" is clicked, only the selection is quoted.

**Signature**

- The signature is appended after a line `-- ` when sending. A checkbox below the editor, checked by default, leaves it out for this post.

**Target groups**

- A new discussion is posted to the current group only. Posting one article to several groups is not part of the MVP.
- A reply is posted to the groups in the parent's `Followup-To`, otherwise in its `Newsgroups`. They are shown in the editor and cannot be edited.
- If the parent has `Followup-To: poster`, its author asked for replies by email. Replying is disabled with that explanation; email is not part of Wardroom.
- In groups that do not allow posting ([spec 003](../003-newsgroup-discovery/spec.md)), "New discussion" and "Reply" are hidden; the group's badge explains why. In moderated groups the editor shows "Your post is sent to the moderator and appears after approval".

**Checks before sending**

- Subject and text are required. Articles are limited to 64 KB.
- If most of the text is quoted, a warning is shown, because some servers reject such posts; sending is still possible.

**Drafts**

- Drafts are not saved. Leaving an editor that contains text asks "Discard draft?".

**Sending and errors**

- On success, the editor closes and the post appears in the thread (see below).
- On failure, the server's message is shown with a hint and a "try again" button. Trying again sends the same article with the same `Message-ID`, so a retry cannot create a duplicate. There is no automatic retry.

| Situation | Hint |
|---|---|
| Posting not allowed (`440`, or the server does not offer posting) | "Your provider does not allow posting with this account. Some providers require posting to be activated in the account settings, or do not include it in trial or block accounts. Check your provider's account page or support." |
| Authentication required (`480`) | "This server requires a login for posting. Add username and password in the server settings." With a link to the server settings |
| Posting failed (`441`) | The server's message, for example a rejected header or too much quoted text |
| Server unreachable, timeout, circuit breaker open | "The server could not be reached. Your text is kept; try again." |

**Connection test**

- The connection test of [spec 002](../002-nntp-server-configuration/spec.md) also reports whether the server offers posting for the configured account: by the `POST` capability after authentication, or by the greeting (`200` allows posting, `201` does not) if `CAPABILITIES` is not supported. The server settings show "Posting: available" or "Posting: not offered by this server" with the hint above. This is informational: servers may still refuse a post later.

**The owner's post**

- After a successful `POST`, the article is stored at once with its headers and body, threaded under its parent and marked read. The group is then synchronized immediately.
- Until the server lists the article in the group, the thread page shows it in its place marked "Sent". After a new discussion, the owner lands on its thread page.
- When synchronization brings the article's overview, it is matched by `Message-ID` and becomes a normal article of the group.
- An article that never appears, for example rejected by a moderator, stays marked "Sent" until the group is unsubscribed.

## The generated article

| Header | Value |
|---|---|
| `From` | `Display Name <email>`; the name is RFC 2047 encoded if it contains non-ASCII characters |
| `Newsgroups` | The target groups, comma-separated |
| `Subject` | RFC 2047 encoded if it contains non-ASCII characters |
| `Message-ID` | `<{random}@{domain of the posting email}>`; `{random}` is 20 characters from a cryptographically secure random source |
| `Date` | RFC 5322 date in UTC |
| `References` | Replies only: the parent's `References` followed by the parent's `Message-ID`. Over 998 characters, middle entries are dropped, keeping the first and the last three (RFC 5537) |
| `MIME-Version` | `1.0` |
| `Content-Type` | `text/plain; charset=UTF-8; format=flowed` |
| `Content-Transfer-Encoding` | `8bit` |
| `User-Agent` | `Wardroom/{version}` |

**Body**

- UTF-8, `format=flowed` (RFC 3676): trailing spaces typed by the owner are removed, then text lines are wrapped at 72 characters with soft line breaks, and lines are space-stuffed where required. Quoted lines are not rewrapped.
- The NNTP adapter sends CRLF line endings and applies dot-stuffing.

## API contracts

All endpoints require a session and the CSRF token for state-changing requests ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457).

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/servers/{id}/identity` | | `200` identity, or `null` fields if none; `404` |
| `PUT /api/servers/{id}/identity` | `{ "name", "email", "signature" }` | `200`; `400` validation; `404` |
| `POST /api/groups/{id}/articles` | Article draft | `201 { "articleId", "rootId", "messageId" }`; `400` validation; `404` unknown group or parent; `409` no identity, posting not allowed in the group, or `Followup-To: poster`; `502` posting failed |

**Article draft**

```json
{
  "parentId": 1234,
  "subject": "Re: Records vs. Lombok",
  "body": "Jane Doe wrote:\n> …\n\nMy answer…",
  "includeSignature": true,
  "messageId": null
}
```

`parentId` is `null` for a new discussion. `messageId` is `null` on the first attempt; a retry sends the `messageId` from the failed response.

A `502` carries `type` (`POSTING_NOT_ALLOWED`, `AUTH_REQUIRED`, `POSTING_FAILED`, `UNREACHABLE`), the server's message and the `messageId` to retry with.

The connection test result of spec 002 gains `postingAvailable` (`true`, `false`, or `null` when the test failed).

## Technical design

- Posting uses the connection reserved for interactive actions ([spec 005](../005-article-synchronization/spec.md)). It is a direct user action and is not retried (ADR 0006).
- Target groups, quoting and the attribution line are built from the parent's stored headers and body; the parent's content is always cached when its "Reply" button is available.
- The article is built and encoded in the backend; the frontend sends the plain draft.
- The owner's article is threaded on insert with the rules of spec 006 and included in later recomputations of the group it was posted from. Until it has a location in the group, it is shown in place as for crossposts and does not count towards message or unread counts.

## Data model changes

- New columns on `nntp_server`: `posting_name`, `posting_email`, `posting_signature`, all nullable.
- New column `article.posted_from_newsgroup_id`: FK `newsgroup`, set for articles posted with Wardroom; marks the owner's articles that are shown before they have a location.
- Unsubscribing also deletes the group's posted articles that have no location.

## Error handling

See "Sending and errors". In addition:

| Situation | Behavior |
|---|---|
| Parent has no cached content | `409`; does not occur through the UI |
| Retry with a `Message-ID` that the server already has (`441` duplicate) | Treated as success: the earlier attempt reached the server |
| Synchronization after posting fails | The post stays marked "Sent"; the next scheduled synchronization picks it up |

## Security considerations

- Line breaks and other control characters are removed from the name, email, subject and signature before they are used in headers, so a value cannot add headers.
- Group names taken from the parent's `Newsgroups` and `Followup-To` are checked against the newsgroup name syntax; invalid entries are dropped.
- The `Message-ID` on retry is accepted only if it has the form Wardroom generates for this server's email domain.
- The draft is plain text and rendered as text in the editor; nothing is interpreted as HTML.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- Headers: RFC 2047 encoding of name and subject, `References` construction and trimming, `Message-ID` form, `Content-Type`.
- Body: flowed wrapping at 72 characters, quoted lines not rewrapped, space-stuffing, dot-stuffing and CRLF on the wire, emojis intact.
- Target groups from `Followup-To` and `Newsgroups`; `Followup-To: poster` rejected; invalid group names dropped.
- Responses `240`, `440`, `441`, `480` and unreachable map to the documented results; a retry reuses the `Message-ID`; a duplicate on retry counts as success.
- The owner's article is stored, threaded and read; synchronization turns it into a normal article; unsubscribe deletes it if it has no location.
- Connection test reports posting availability from capabilities and from the greeting.
- Header injection: line breaks in name and subject do not produce extra headers.

Frontend component tests:

- Inline identity prompt, quoting with and without a selection, signature checkbox, quoted-text warning, discard confirmation, hints per error type.

## Implementation tasks

1. Flyway migration for the identity columns and `article.posted_from_newsgroup_id`.
2. Identity endpoints and settings section.
3. Article builder: headers, encoding, flowed body, `References` trimming.
4. NNTP adapter: `POST` with dot-stuffing and response mapping; posting availability in the connection test.
5. Posting endpoint: storing and threading the owner's article, immediate synchronization, retry.
6. Frontend: new discussion page, inline reply editor, quoting, identity prompt, errors and hints, "Sent" marker.
