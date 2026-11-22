package io.github.stekhub.wardroom.articles;

/** What the synchronization needs of the cache size limit (ADR 0023). */
public interface ArticleCache {

    /**
     * Checks the size of the database against the limit in the background and deletes cached article content
     * while it is above it. To be called after a synchronization run. Returns at once; a check that is already
     * running is followed by one more.
     */
    void cleanUpSoon();
}
