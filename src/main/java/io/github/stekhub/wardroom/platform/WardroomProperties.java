package io.github.stekhub.wardroom.platform;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param dataDir the directory that holds all persistent state: the database and its backups (ADR 0009). It is
 *                {@code /config} in the container.
 */
@Validated
@ConfigurationProperties("wardroom")
public record WardroomProperties(@NotNull Path dataDir) {

    public Path databaseFile() {
        return dataDir.resolve("wardroom.db");
    }

    public Path backupDirectory() {
        return dataDir.resolve("backups");
    }
}
