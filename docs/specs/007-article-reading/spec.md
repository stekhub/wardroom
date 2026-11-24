# 007. Article reading

- Status: Accepted
- Date: 2026-11-18
- Related: brief §3.6, §3.8, [UI concept](../../product/ui-concept.md), [ADR 0012](../../architecture/decisions/0012-data-model.md), [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0014](../../architecture/decisions/0014-plain-text-article-display.md), [spec 005](../005-article-synchronization/spec.md), [spec 006](../006-threading/spec.md)

## Scope

Fetching and caching article content, extracting and decoding the plain-text body, marking articles as read, and how article text is displayed: quotes, signatures, links, headers. Which content is displayed at all is decided in ADR 0014. The thread page layout is covered by [spec 008](../008-discussion-view/spec.md).

## User stories

- As the owner, I open a thread and its articles' text appears without further clicks.
- As the owner, I read old posts with broken or missing charset information without garbled text.
- As the owner, I can tell quotes, replies and signatures apart at a glance.
- As the owner, I follow links in articles, and nothing in an article can harm my browser or my instance.

## Functional requirements

**Fetching**

- Content is fetched with `ARTICLE <message-id>`, for normal articles, crossposts and stubs alike, over the connection reserved for interactive actions ([spec 005](../005-article-synchronization/spec.md)).
- On the thread page, the structure is shown at once from stored metadata. An article's content is fetched when it scrolls into view; cached content is never fetched again (ADR 0013).
- A fetched stub becomes a normal article: its metadata is filled from the headers, and its thread is recomputed ([spec 006](../006-threading/spec.md)).

**Read state**

- An article is marked read when its body has been displayed on the thread page.
- "Mark as unread" in the menu of an article removes its read state again ([spec 014](../014-next-unread-and-mark-as-unread/spec.md)).

**Body extraction and decoding**

- The displayed body follows ADR 0014: the single-part body or the first `text/plain` part; everything else is replaced by an omitted-content notice.
- Decoding uses Mime4j. A missing or unknown charset is decoded as UTF-8, falling back to windows-1252 if that fails. Invalid byte sequences are replaced, never rejected.
- `format=flowed` (RFC 3676) bodies are stored as received and unwrapped at display time. Whether the text was sent with `DelSp=yes` is stored with it: the space before a soft line break is then not part of the text.
- Stored text is limited to 1 MB per article; longer text is cut off with a notice.

**Display**

- Line breaks are kept; text wraps to the available width.
- Quoted lines (starting with `>`) get a colored bar per quote level. Quoted blocks longer than 5 lines are collapsed behind "show quoted text".
- The signature (everything after a line consisting of `-- `) is shown dimmed.
- `http` and `https` URLs are clickable. No other schemes are linked. A click does not open the target itself but the leaving page, see below.
- Where a link ends:
  - It starts at `http://` or `https://` and runs to the next whitespace, `<`, `>` or double quote.
  - Punctuation at its end does not belong to it: `.` `,` `;` `:` `!` `?` and quotes.
  - A closing bracket `)` `]` `}` at its end does not belong to it, unless the address has the matching opening bracket.
  - An address written as `<https://…>` that is broken over up to 5 lines is one link.
  - An address without angle brackets ends at the line end.
- A per-article "fixed width" toggle switches to a monospace font for ASCII art and tables; it is not remembered.
- A per-article "show all headers" toggle shows the stored raw header block.

**Leaving page**

- A link in an article opens the leaving page of Wardroom in a new tab, with `rel="noopener noreferrer"`. The tab with the article keeps its reading position.
- The page has the path `/leaving` and gets the target address as the query parameter `url`. It needs a login like every other view.
- It says "You are leaving Wardroom" and shows the complete target address as plain text. The domain is set apart from the rest of the address, because a long address can hide where it leads.
- "Continue" loads the target in the same tab. "Stay" closes the tab; where the browser does not allow a page to close its tab, it leads to the start page of Wardroom.
- The page checks the address again. For anything but a valid `http` or `https` address it says "This link cannot be opened" and offers no "Continue".
- Every link asks. There is no "do not ask again" and no list of trusted sites in the MVP.

**Card excerpt**

- The first 200 characters of the card article's cached body, without quoted lines and signature. Without cached content the card has no excerpt (ADR 0013).

**Unavailable articles**

- If the server answers `430` (no such article), the article shows "no longer available on the server" and the time of the attempt is stored. Opening it again does not retry; a "try again" button does.

## API contracts

All endpoints require a session and the CSRF token for state-changing requests ([spec 001](../001-user-authentication/spec.md)). Errors use Problem Details (RFC 9457).

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/articles/{id}/content` | | `200` content, fetched from the server if not cached; `404` unknown article; `410` not available on the server; `502` server error |
| `POST /api/articles/{id}/content/retry` | | `200` content; `404`; `410`; `502` |
| `PUT /api/articles/{id}/read` | | `204`; `404` |

**Content**

```json
{
  "articleId": 1234,
  "body": "Plain text…",
  "flowed": false,
  "delSp": false,
  "truncated": false,
  "omittedParts": [{ "name": "photo.jpg", "mediaType": "image/jpeg", "size": 245000 }],
  "rawHeaders": "Path: …"
}
```

## Technical design

- Fetch, decode and store run during the request. Concurrent requests for the same article share one fetch.
- The frontend requests content per article as it scrolls into view and marks it read once it is rendered.
- Quote levels, signature, links and `format=flowed` are computed in the frontend from the plain text and rendered as React text nodes and elements, never as HTML strings.

## Data model changes

- New column `article.content_unavailable_at`: set when the server answered `430`; cleared by a successful retry.
- New columns `article.body_flowed`, `article.body_delsp` and `article.body_truncated`: what the content response returns as `flowed`, `delSp` and `truncated`.

## Error handling

| Situation | Behavior |
|---|---|
| `430` no such article | `410`; `content_unavailable_at` set |
| Server unreachable, timeout, circuit breaker open | `502`; the article shows the error and a "try again" button; nothing is stored |
| `481` authentication failed | `502`; `credentials_invalid` set ([spec 002](../002-nntp-server-configuration/spec.md)) |
| MIME structure cannot be parsed | The raw body is decoded as plain text with the fallback charsets |
| Limits exceeded (see below) | Processing stops at the limit; what was extracted is stored with an omitted-content notice |

## Security considerations

Article content is untrusted. Nothing in it is ever executed, rendered as HTML or used to load anything:

- **Not stored, not sent to the browser:** HTML parts, attachments and binary content. Only their name, media type and size are kept, for the notice (ADR 0014). Attachments are never decoded, saved as files or offered for download.
- **Rendered as text:** the body and all headers are inserted as escaped text. `dangerouslySetInnerHTML` is never used for article data; links are built as React elements from URLs that passed the `http`/`https` check.
- **Leaving page:** anyone can build a link to it with any target, so it trusts nothing in its address: the target is checked again, shown as text only and never opened without a click. The target site gets no referrer and no handle on a Wardroom tab.
- **No remote resources:** no images, styles or other resources referenced by an article are loaded.
- **Content Security Policy** on all pages: scripts only from Wardroom itself, no inline scripts; styles from Wardroom itself and inline, because UI components insert their own styles; images only from Wardroom itself and `data:`; the pages cannot be shown inside a frame. This is a second line of defense if escaping ever fails.
- **Further headers:** `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` on all responses.
- **Names are text only:** attachment names and other header values are never used as file paths or in commands.
- **Parser limits** against crafted messages: at most 1 MB of decoded text, 100 MIME parts, nesting depth 10, and 10 MB of raw article size read from the server; anything beyond is not processed.

## Testing requirements

Integration tests against a real SQLite database and the mock NNTP server (ADR 0008):

- Content is fetched once and then served from the cache; concurrent requests cause one fetch.
- A stub is filled in and rethreaded.
- `430` sets `content_unavailable_at` and returns `410`; retry clears it on success.
- Multipart article: first `text/plain` part shown, other parts listed as omitted and not stored; HTML-only article shows the notice.
- Charsets: declared charsets, missing charset with UTF-8 and with windows-1252 content, invalid sequences replaced.
- Limits: oversized text is truncated; too many parts and deep nesting stop processing without failure.

Frontend component tests:

- Quote levels and collapsing, signature, links with only `http`/`https`, `format=flowed` unwrapping, fixed-width toggle.
- A link in an article leads to the leaving page, not to the target.
- Leaving page: it shows the target address and its domain; "Continue" leads to the target; an address with another scheme or an invalid one offers no "Continue".
- Article text containing HTML and script tags is shown literally.

## Implementation tasks

1. Flyway migration for `article.content_unavailable_at`.
2. NNTP adapter: `ARTICLE <message-id>` with the size limit.
3. Content extraction with Mime4j: part selection, charset fallback, limits, omitted parts, stub filling.
4. Content, retry and read endpoints.
5. Content Security Policy header.
6. Frontend: lazy content loading on the thread page, read marking, article text rendering, toggles, author icons, card excerpt.
7. Frontend: the leaving page.
