package io.github.stekhub.wardroom.settings;

/**
 * What the settings page shows of the cache, and how the cache learns that its settings changed (ADR 0023). The
 * module that cleans the cache implements it.
 */
public interface CacheUse {

    /** @return the space the database uses now, in bytes */
    long usedBytes();

    /**
     * @return whether the limit applies and the database is above it although no cached content is left to delete
     */
    boolean limitUnreachable();

    /** Called after the settings were saved. The cache is checked against them in the background. */
    void settingsChanged();
}
