-- Ignored groups (spec 022) and ignored words in subjects (spec 023; data model).

-- The kinds of a rule are a constraint, which SQLite cannot change: the table is rebuilt (ADR 0004).
PRAGMA defer_foreign_keys = ON;

CREATE TABLE ignore_rule_new (
    id           INTEGER PRIMARY KEY,
    kind         TEXT   NOT NULL CHECK (kind IN ('AUTHOR', 'THREAD', 'GROUP', 'SUBJECT')),
    -- AUTHOR: the author key. THREAD: the Message-ID its root had. GROUP: the group name.
    -- SUBJECT: the word or phrase, normalized like the subject words of an article.
    value        TEXT   NOT NULL,
    -- What the settings page shows: the name of the author, the subject of the thread, the group name, or the
    -- word as the owner typed it.
    label        TEXT   NOT NULL,
    created_at   BIGINT NOT NULL,
    -- Only for a SUBJECT rule of one group; NULL for all groups and for the other kinds. The rule goes with the
    -- group when its server is removed.
    newsgroup_id INTEGER REFERENCES newsgroup (id) ON DELETE CASCADE
);

INSERT INTO ignore_rule_new (id, kind, value, label, created_at)
SELECT id, kind, value, label, created_at
FROM ignore_rule;

DROP TABLE ignore_rule;

ALTER TABLE ignore_rule_new RENAME TO ignore_rule;

-- A rule exists once. "All groups" counts as one value, which a plain unique key would not do for NULL.
CREATE UNIQUE INDEX ignore_rule_kind_value_newsgroup ON ignore_rule (kind, value, COALESCE(newsgroup_id, 0));

-- The subject as the ignored words are compared with it: lowercased, every character that is no letter and no
-- digit replaced by a space, runs of spaces made one, and one space before and after. NULL for stubs. The
-- application computes it when an article is stored or a stub is filled in, and at its start for the articles
-- that were stored before this column existed.
ALTER TABLE article ADD COLUMN subject_words TEXT;
