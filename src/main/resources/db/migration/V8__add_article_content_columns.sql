-- The content of an article, fetched when the article is read (spec 007; ADR 0014; data model). All columns are
-- NULL until the content has been fetched.

-- The header block as received.
ALTER TABLE article ADD COLUMN raw_headers TEXT;

-- The decoded plain text. HTML parts, attachments and binary content are never stored.
ALTER TABLE article ADD COLUMN body_text TEXT;

-- Whether the text is "format=flowed" (RFC 3676); it is stored as received and unwrapped when it is displayed.
ALTER TABLE article ADD COLUMN body_flowed BOOLEAN;

-- Whether the flowed text was sent with "DelSp=yes": the space before a soft line break is not part of the text.
ALTER TABLE article ADD COLUMN body_delsp BOOLEAN;

-- Whether a limit stopped the processing, so that the stored text is not all of the article.
ALTER TABLE article ADD COLUMN body_truncated BOOLEAN;

-- The parts that are not displayed, as a JSON list of name, media type and size.
ALTER TABLE article ADD COLUMN omitted_parts TEXT;

-- When the content was fetched, in milliseconds.
ALTER TABLE article ADD COLUMN content_fetched_at BIGINT;

-- When the server answered that it no longer has the article (430), in milliseconds. Cleared by a successful
-- fetch.
ALTER TABLE article ADD COLUMN content_unavailable_at BIGINT;
