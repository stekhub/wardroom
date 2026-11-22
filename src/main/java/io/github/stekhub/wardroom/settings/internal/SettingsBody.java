package io.github.stekhub.wardroom.settings.internal;

import io.github.stekhub.wardroom.settings.CacheSettings;
import io.github.stekhub.wardroom.settings.SynchronizationSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** The settings as the settings endpoints send and receive them (spec 005). */
@Schema(name = "Settings")
record SettingsBody(
        @Schema(description = "How often all subscribed groups are synchronized, in minutes; 5 to 1440")
        @NotNull Integer syncIntervalMinutes,
        @Schema(description = "How many overviews a first synchronization fetches at most; 50 to 10000")
        @NotNull Integer initialDepth,
        @Schema(description = "How often the group list is fetched, in hours; 1 to 168")
        @NotNull Integer groupListIntervalHours,
        @Schema(description = "Whether the cache size limit applies (ADR 0034)")
        @NotNull Boolean cacheLimitEnabled,
        @Schema(description = "The cache size limit in gigabytes; 1 to 1000. It is kept while the limit is off")
        @NotNull Integer maxCacheSizeGb,
        @Schema(description = "The space the database uses now, in bytes",
                accessMode = Schema.AccessMode.READ_ONLY)
        Long cacheUsedBytes,
        @Schema(description = "Whether the database is above the limit although no cached content is left to"
                + " delete", accessMode = Schema.AccessMode.READ_ONLY)
        Boolean cacheLimitUnreachable) {

    static SettingsBody of(SynchronizationSettings settings, CacheSettings cacheSettings, long cacheUsedBytes,
            boolean cacheLimitUnreachable) {
        return new SettingsBody(settings.syncIntervalMinutes(), settings.initialDepth(),
                settings.groupListIntervalHours(), cacheSettings.limitEnabled(), cacheSettings.maxSizeGb(),
                cacheUsedBytes, cacheLimitUnreachable);
    }
}
