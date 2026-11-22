package io.github.stekhub.wardroom.settings;

/**
 * The instance settings of the cache size limit (ADR 0023, ADR 0034).
 *
 * @param limitEnabled whether the limit applies; without it, no cached content is deleted
 * @param maxSizeGb    the limit in gigabytes; it keeps its value while the limit is off
 */
public record CacheSettings(boolean limitEnabled, int maxSizeGb) {

    /** The values of an installation in which nothing was changed. */
    public static final CacheSettings DEFAULTS = new CacheSettings(true, 5);

    private static final long BYTES_PER_GIGABYTE = 1024L * 1024 * 1024;

    /** @return the limit in bytes */
    public long maxSizeBytes() {
        return maxSizeGb * BYTES_PER_GIGABYTE;
    }
}
