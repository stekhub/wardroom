-- Articles the owner posted with Wardroom (spec 009; data model).

-- The group an article was posted from. Until the synchronization finds the article in that group, it has no
-- location there and is shown in its thread as "Sent". NULL for every article that was not posted with Wardroom.
-- When the group is deleted with its server, the mark goes with it.
ALTER TABLE article ADD COLUMN posted_from_newsgroup_id INTEGER REFERENCES newsgroup (id) ON DELETE SET NULL;

-- Finds the posted articles of a group. Few articles are posted, so the index leaves the others out.
CREATE INDEX article_posted_from ON article (posted_from_newsgroup_id)
    WHERE posted_from_newsgroup_id IS NOT NULL;
