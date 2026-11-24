-- Two-factor authentication (spec 012).
-- The TOTP secret, encrypted with the key file (ADR 0035): one version byte, the nonce, then ciphertext and tag.
ALTER TABLE wardroom_user ADD COLUMN totp_secret BLOB;
ALTER TABLE wardroom_user ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- The 30-second period of the last accepted code, so a code cannot be used twice.
ALTER TABLE wardroom_user ADD COLUMN totp_last_used_period BIGINT;

-- One-time recovery codes of the single account, stored only as SHA-256 hashes. Emptied with the account.
CREATE TABLE recovery_code (
    id        INTEGER PRIMARY KEY,
    code_hash TEXT NOT NULL UNIQUE
);
