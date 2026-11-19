-- The local Wardroom account (data model: wardroom_user). The MVP has exactly one row.
CREATE TABLE wardroom_user (
    id            INTEGER PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL
);
