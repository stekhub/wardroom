-- The login throttling keeps its state with the account, so it survives a restart (spec 011). A password reset
-- deletes the account row and the state with it.
ALTER TABLE wardroom_user ADD COLUMN failed_logins INTEGER NOT NULL DEFAULT 0;
-- Milliseconds since 1970; BIGINT so that the generated database classes use 64-bit numbers.
ALTER TABLE wardroom_user ADD COLUMN login_blocked_until BIGINT;
