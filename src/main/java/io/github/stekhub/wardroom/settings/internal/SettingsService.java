package io.github.stekhub.wardroom.settings.internal;

import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.settings.CacheSettings;
import io.github.stekhub.wardroom.settings.Settings;
import io.github.stekhub.wardroom.settings.SynchronizationSettings;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Reads, checks and stores the instance settings (spec 005). */
@Slf4j
@Service
@RequiredArgsConstructor
class SettingsService implements Settings {

    static final String SYNC_INTERVAL_MINUTES = "sync.interval_minutes";
    static final String INITIAL_DEPTH = "sync.initial_depth";
    static final String GROUP_LIST_INTERVAL_HOURS = "sync.group_list_interval_hours";

    static final int SYNC_INTERVAL_MIN = 5;
    static final int SYNC_INTERVAL_MAX = 1440;
    static final int INITIAL_DEPTH_MIN = 50;
    static final int INITIAL_DEPTH_MAX = 10_000;
    static final int GROUP_LIST_INTERVAL_MIN = 1;
    static final int GROUP_LIST_INTERVAL_MAX = 168;

    static final String CACHE_LIMIT_ENABLED = "cache.limit_enabled";
    static final String MAX_CACHE_SIZE_GB = "cache.max_size_gb";
    static final int MAX_CACHE_SIZE_MIN = 1;
    static final int MAX_CACHE_SIZE_MAX = 1000;

    private final SettingsRepository settingsRepository;
    private final TransactionOperations transactionOperations;

    /** A setting that is not stored, or whose stored value is outside its range, has its default. */
    @Override
    public SynchronizationSettings synchronization() {
        Map<String, String> storedValues = settingsRepository.findAll();
        SynchronizationSettings defaults = SynchronizationSettings.DEFAULTS;
        return new SynchronizationSettings(
                storedOrDefault(storedValues, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MIN, SYNC_INTERVAL_MAX,
                        defaults.syncIntervalMinutes()),
                storedOrDefault(storedValues, INITIAL_DEPTH, INITIAL_DEPTH_MIN, INITIAL_DEPTH_MAX,
                        defaults.initialDepth()),
                storedOrDefault(storedValues, GROUP_LIST_INTERVAL_HOURS, GROUP_LIST_INTERVAL_MIN,
                        GROUP_LIST_INTERVAL_MAX, defaults.groupListIntervalHours()));
    }

    /** The limit applies unless it was switched off (ADR 0034). */
    @Override
    public CacheSettings cache() {
        Map<String, String> storedValues = settingsRepository.findAll();
        return new CacheSettings(
                !"false".equals(storedValues.get(CACHE_LIMIT_ENABLED)),
                storedOrDefault(storedValues, MAX_CACHE_SIZE_GB, MAX_CACHE_SIZE_MIN, MAX_CACHE_SIZE_MAX,
                        CacheSettings.DEFAULTS.maxSizeGb()));
    }

    /**
     * @return the settings as they are stored now
     * @throws FieldValidationException naming the first value that is outside its range
     */
    SynchronizationSettings save(SettingsBody request) {
        requireInRange("syncIntervalMinutes", request.syncIntervalMinutes(), SYNC_INTERVAL_MIN, SYNC_INTERVAL_MAX,
                "The synchronization interval must be between %d and %d minutes.");
        requireInRange("initialDepth", request.initialDepth(), INITIAL_DEPTH_MIN, INITIAL_DEPTH_MAX,
                "The first synchronization depth must be between %d and %d articles.");
        requireInRange("groupListIntervalHours", request.groupListIntervalHours(), GROUP_LIST_INTERVAL_MIN,
                GROUP_LIST_INTERVAL_MAX, "The group list refresh interval must be between %d and %d hours.");
        requireInRange("maxCacheSizeGb", request.maxCacheSizeGb(), MAX_CACHE_SIZE_MIN, MAX_CACHE_SIZE_MAX,
                "The maximum cache size must be between %d and %d GB.");
        transactionOperations.executeWithoutResult(status -> {
            settingsRepository.store(SYNC_INTERVAL_MINUTES, request.syncIntervalMinutes().toString());
            settingsRepository.store(INITIAL_DEPTH, request.initialDepth().toString());
            settingsRepository.store(GROUP_LIST_INTERVAL_HOURS, request.groupListIntervalHours().toString());
            settingsRepository.store(CACHE_LIMIT_ENABLED, request.cacheLimitEnabled().toString());
            settingsRepository.store(MAX_CACHE_SIZE_GB, request.maxCacheSizeGb().toString());
        });
        log.info("The settings were saved: synchronization every {} minutes, first synchronization depth {}, group"
                        + " list every {} hours, cache size limit {}", request.syncIntervalMinutes(),
                request.initialDepth(), request.groupListIntervalHours(),
                request.cacheLimitEnabled() ? request.maxCacheSizeGb() + " GB" : "off");
        return synchronization();
    }

    private static void requireInRange(String field, int value, int minimum, int maximum, String rule) {
        if (value < minimum || value > maximum) {
            throw new FieldValidationException(field, rule.formatted(minimum, maximum));
        }
    }

    private static int storedOrDefault(Map<String, String> storedValues, String key, int minimum, int maximum,
            int defaultValue) {
        String storedValue = storedValues.get(key);
        if (storedValue == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(storedValue.strip());
            return value < minimum || value > maximum ? defaultValue : value;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
