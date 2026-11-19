package io.github.stekhub.wardroom.platform;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the value of {@code WARDROOM_LOG_LEVEL} into logging properties (ADR 0029).
 *
 * <p>The level applies to Wardroom's own log output. Libraries follow it only when it is stricter than {@code INFO};
 * at {@code DEBUG} and {@code TRACE} they stay at {@code INFO}, so the NNTP commands and per-article decisions are not
 * buried under framework output.
 */
public final class LogLevels {

    public static final String ENVIRONMENT_VARIABLE = "WARDROOM_LOG_LEVEL";

    static final String DEFAULT_LEVEL = "INFO";
    static final String ROOT_PROPERTY = "logging.level.root";
    static final String WARDROOM_PROPERTY = "logging.level.io.github.stekhub.wardroom";
    static final String INVALID_VALUE_PROPERTY = "wardroom.logging.invalid-level";

    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
    private static final Set<String> STRICTER_THAN_INFO = Set.of("WARN", "ERROR", "OFF");

    private LogLevels() {
    }

    /**
     * @param value the value of the environment variable, may be {@code null}
     * @return default properties for the application; an unknown value falls back to {@code INFO} and is reported in
     *         {@code wardroom.logging.invalid-level}, so it can be logged once logging is set up
     */
    public static Map<String, Object> fromEnvironmentValue(String value) {
        Map<String, Object> properties = new LinkedHashMap<>();
        String level = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (level.isEmpty()) {
            level = DEFAULT_LEVEL;
        } else if (!LEVELS.contains(level)) {
            properties.put(INVALID_VALUE_PROPERTY, LogSafe.escape(value));
            level = DEFAULT_LEVEL;
        }
        properties.put(WARDROOM_PROPERTY, level);
        properties.put(ROOT_PROPERTY, STRICTER_THAN_INFO.contains(level) ? level : DEFAULT_LEVEL);
        return properties;
    }
}
