-- The activity of a group: how many articles it gets per day (spec 003; data model).

-- Two baselines, each a time and the high watermark that the group list refresh read at that time. The
-- column high_watermark cannot serve, because the synchronization of a subscribed group moves it. All four
-- are NULL until the first refresh after this migration.
ALTER TABLE newsgroup ADD COLUMN activity_older_at BIGINT;
ALTER TABLE newsgroup ADD COLUMN activity_older_high_watermark BIGINT;
ALTER TABLE newsgroup ADD COLUMN activity_younger_at BIGINT;
ALTER TABLE newsgroup ADD COLUMN activity_younger_high_watermark BIGINT;

-- Computed by the refresh from the older baseline. NULL while the older baseline is younger than a day.
ALTER TABLE newsgroup ADD COLUMN articles_per_day DOUBLE;
