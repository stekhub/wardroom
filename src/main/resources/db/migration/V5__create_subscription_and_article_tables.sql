-- Subscriptions and what they need of the articles (spec 004; data model). The tables for articles have the
-- columns that subscriptions use; the synchronization and later features add theirs.

-- A subscribed group. A group has at most one subscription.
CREATE TABLE subscription (
    id           INTEGER PRIMARY KEY,
    newsgroup_id INTEGER NOT NULL UNIQUE REFERENCES newsgroup (id)
);

-- One row per Message-ID.
CREATE TABLE article (
    id         INTEGER PRIMARY KEY,
    -- Verbatim, with the angle brackets; compared exactly.
    message_id TEXT    NOT NULL UNIQUE,
    -- True for an article that is only referenced and not stored.
    is_stub    BOOLEAN NOT NULL DEFAULT FALSE
);

-- Where an article appears. A crossposted article has one row per group.
CREATE TABLE article_location (
    id             INTEGER PRIMARY KEY,
    newsgroup_id   INTEGER NOT NULL REFERENCES newsgroup (id),
    -- Article numbers and times in milliseconds are BIGINT, so that the generated database classes use 64-bit
    -- numbers.
    article_number BIGINT  NOT NULL,
    article_id     INTEGER NOT NULL REFERENCES article (id),
    -- Set when the number falls below the low watermark of the server.
    expired_at     BIGINT,
    UNIQUE (newsgroup_id, article_number),
    UNIQUE (newsgroup_id, article_id)
);

-- Finds the locations of one article, to tell whether an article has a location left.
CREATE INDEX article_location_article ON article_location (article_id);

-- A row means that the article has been read. It is deleted with the article.
CREATE TABLE read_state (
    id         INTEGER PRIMARY KEY,
    article_id INTEGER NOT NULL UNIQUE REFERENCES article (id) ON DELETE CASCADE,
    read_at    BIGINT  NOT NULL
);

-- A range of article numbers whose overviews have been fetched (ADR 0021). Both ends belong to the range.
CREATE TABLE synced_range (
    id           INTEGER PRIMARY KEY,
    newsgroup_id INTEGER NOT NULL REFERENCES newsgroup (id),
    first_number BIGINT  NOT NULL,
    last_number  BIGINT  NOT NULL
);

CREATE INDEX synced_range_newsgroup ON synced_range (newsgroup_id);
