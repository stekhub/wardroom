package io.github.stekhub.wardroom.settings;

/** The settings the owner edits on the settings page, for the modules that work with them. */
public interface Settings {

    /**
     * @return the current values. They are read for every call, so a change takes effect without a restart; the
     *         caller does not keep them
     */
    SynchronizationSettings synchronization();

    /** @return the current settings of the cache size limit, read for every call */
    CacheSettings cache();

    /**
     * @return whether the group list leaves out binary groups (spec 022), read for every call. It does unless the
     *         owner switched it off
     */
    boolean hideBinaryGroups();
}
