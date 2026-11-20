package io.github.stekhub.wardroom.settings;

/**
 * The instance settings of the synchronization (spec 005).
 *
 * @param syncIntervalMinutes    how often all subscribed groups are synchronized
 * @param initialDepth           how many overviews a first synchronization fetches at most
 * @param groupListIntervalHours how often the group list of a server is fetched (ADR 0020)
 */
public record SynchronizationSettings(int syncIntervalMinutes, int initialDepth, int groupListIntervalHours) {

    /** The values of an installation in which nothing was changed. */
    public static final SynchronizationSettings DEFAULTS = new SynchronizationSettings(15, 500, 24);
}
