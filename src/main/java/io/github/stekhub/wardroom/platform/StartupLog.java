package io.github.stekhub.wardroom.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Logs the start with the version (ADR 0029). */
@Slf4j
@Component
@RequiredArgsConstructor
class StartupLog {

    private final ObjectProvider<BuildProperties> buildProperties;
    private final WardroomProperties properties;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    void logStart() {
        BuildProperties build = buildProperties.getIfAvailable();
        log.info("Wardroom {} started; data directory {}", build == null ? "(development build)" : build.getVersion(),
                properties.dataDir().toAbsolutePath().normalize());
        String invalidLevel = environment.getProperty(LogLevels.INVALID_VALUE_PROPERTY);
        if (invalidLevel != null) {
            log.warn("{}={} is not a log level; using {}. Valid values: TRACE, DEBUG, INFO, WARN, ERROR, OFF",
                    LogLevels.ENVIRONMENT_VARIABLE, invalidLevel, LogLevels.DEFAULT_LEVEL);
        }
    }
}
