-- Ignored authors and threads (spec 010; data model).

-- An author or a thread the owner ignores. A rule matches articles by its value, not by a reference:
-- for an author the author key, for a thread the Message-ID its root had when the rule was created.
CREATE TABLE ignore_rule (
    id         INTEGER PRIMARY KEY,
    kind       TEXT   NOT NULL CHECK (kind IN ('AUTHOR', 'THREAD')),
    value      TEXT   NOT NULL,
    -- The display name of the author or the subject of the thread, for the settings page.
    label      TEXT   NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (kind, value)
);

-- The key that stands for the author: the email address of "from", lowercased and trimmed, else the display
-- name. NULL for stubs. The application computes it when an article is stored or a stub is filled in, and at its
-- start for the articles that were stored before this column existed.
ALTER TABLE article ADD COLUMN author_key TEXT;

-- Finds the articles of an ignored author.
CREATE INDEX article_author_key ON article (author_key);
