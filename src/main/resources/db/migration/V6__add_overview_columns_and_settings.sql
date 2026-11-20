-- What the synchronization stores of an article, and the instance settings (spec 005; data model).

-- The fields of an overview. All are NULL for a stub. Two names are SQL keywords and therefore quoted.
ALTER TABLE article ADD COLUMN subject TEXT;
ALTER TABLE article ADD COLUMN "from" TEXT;
-- UTC, in milliseconds; NULL when the date of the article cannot be read.
ALTER TABLE article ADD COLUMN date BIGINT;
ALTER TABLE article ADD COLUMN "references" TEXT;
ALTER TABLE article ADD COLUMN bytes BIGINT;
ALTER TABLE article ADD COLUMN lines BIGINT;

-- Instance-wide settings as key-value pairs.
CREATE TABLE app_setting (
    id    INTEGER PRIMARY KEY,
    key   TEXT NOT NULL UNIQUE,
    value TEXT NOT NULL
);

INSERT INTO app_setting (key, value)
VALUES ('sync.interval_minutes', '15'),
       ('sync.initial_depth', '500'),
       ('sync.group_list_interval_hours', '24');
