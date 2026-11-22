CREATE TABLE note (id INTEGER PRIMARY KEY, text TEXT NOT NULL);

-- As in the real schema: the stored version of Wardroom (ADR 0032) is kept here.
CREATE TABLE app_setting (id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE, value TEXT NOT NULL);
