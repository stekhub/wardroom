package io.github.stekhub.wardroom.settings.internal;

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
        @NotNull Integer groupListIntervalHours) {

    static SettingsBody of(SynchronizationSettings settings) {
        return new SettingsBody(settings.syncIntervalMinutes(), settings.initialDepth(),
                settings.groupListIntervalHours());
    }
}
