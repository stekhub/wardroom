# 0014. Plain-text article display

- Status: Accepted; links and quote display decided in [spec 007](../../specs/007-article-reading/spec.md)
- Date: 2026-11-18

## Context

Wardroom is strictly a text discussion client (brief §3.8). Incoming articles can contain HTML, attachments and encoded binaries. Rendering or storing such content adds security risk and storage without serving the product.

## Decision

**Display**

- Only plain text is displayed.
- The body is the article's `text/plain` content: for a single-part article its body, for a MIME multipart article its first `text/plain` part. It is decoded from its charset and transfer encoding to UTF-8 with Mime4j.
- Everything else is not displayed and is replaced by a notice:
  - articles without plain text (for example HTML-only): "HTML message, not displayed";
  - attachments and other parts: "1 attachment omitted (name, size)";
  - binary content embedded in a text body (yEnc, uuencode): an omitted-content notice.
- Article text is always rendered as escaped text. Article content is never inserted as HTML (`dangerouslySetInnerHTML` is not used for it), and no remote resources are loaded.

**Storage**

- Stored: the raw header block and the decoded plain-text body.
- Not stored: attachments, HTML parts and binary content. Only their name, media type and size are kept, for the notice.

## Consequences

- No HTML or attachment content ever reaches the database or the browser.
- Improving text extraction later requires fetching the article again, because the raw body is not kept.
- Clickable links and collapsible quote levels remain an open question; both can be built on top of plain text.
