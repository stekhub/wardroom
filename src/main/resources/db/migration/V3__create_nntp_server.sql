-- The NNTP server configuration (data model: nntp_server). The MVP allows exactly one row, which the application
-- enforces. The columns for the group list refresh and the posting identity are filled by later features.
CREATE TABLE nntp_server (
    id                   INTEGER PRIMARY KEY,
    name                 TEXT    NOT NULL,
    host                 TEXT    NOT NULL,
    port                 INTEGER NOT NULL,
    tls_mode             TEXT    NOT NULL,
    auth_method          TEXT    NOT NULL,
    username             TEXT,
    -- One version byte, the nonce, then ciphertext and tag (spec 002). Never the password itself.
    password_encrypted   BLOB,
    max_connections      INTEGER NOT NULL,
    credentials_invalid  BOOLEAN NOT NULL DEFAULT FALSE,
    -- Milliseconds since 1970; BIGINT so that the generated database classes use 64-bit numbers.
    groups_refreshed_at  BIGINT,
    groups_refresh_error TEXT,
    posting_name         TEXT,
    posting_email        TEXT,
    posting_signature    TEXT
);
