-- The place of an article in its thread (spec 006; data model). Both columns are derived from the stored articles
-- and can be computed again at any time.

-- The article this one answers: a stored article or a stub. NULL for the first article of a thread. When the
-- parent is deleted, the link goes with it; the threads are computed again afterwards.
ALTER TABLE article ADD COLUMN parent_id INTEGER REFERENCES article (id) ON DELETE SET NULL;

-- The topmost ancestor, which can be a stub; the article itself when it has no parent. NULL means that the place
-- of the article has not been computed yet.
ALTER TABLE article ADD COLUMN thread_root_id INTEGER REFERENCES article (id) ON DELETE SET NULL;

-- Finds the replies to an article.
CREATE INDEX article_parent ON article (parent_id);

-- Finds the articles of a thread, and groups the articles of a newsgroup by thread.
CREATE INDEX article_thread_root ON article (thread_root_id);
