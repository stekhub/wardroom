-- The cache cleanup deletes the content of the oldest articles first (ADR 0023). This index finds them without
-- reading the articles that have no content.
CREATE INDEX article_cached_content ON article (date)
    WHERE content_fetched_at IS NOT NULL;
