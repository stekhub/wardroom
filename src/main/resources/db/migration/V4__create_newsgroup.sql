-- A group as it exists on one server (data model: newsgroup). The columns for the synchronization are filled by
-- later features.
CREATE TABLE newsgroup (
    id              INTEGER PRIMARY KEY,
    server_id       INTEGER NOT NULL REFERENCES nntp_server (id),
    name            TEXT    NOT NULL,
    description     TEXT,
    -- ALLOWED, NOT_ALLOWED or MODERATED (spec 003).
    posting_status  TEXT    NOT NULL,
    -- Article numbers and times in milliseconds are BIGINT, so that the generated database classes use 64-bit
    -- numbers.
    low_watermark   BIGINT  NOT NULL,
    high_watermark  BIGINT  NOT NULL,
    last_synced_at  BIGINT,
    last_sync_error TEXT,
    -- Set when the group disappears from the group list of the server. The row is never deleted for that reason.
    removed_at      BIGINT,
    -- Also serves the group list page, which reads the groups of one server ordered by name.
    UNIQUE (server_id, name)
);
