package io.github.stekhub.wardroom.platform;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param dataDir           the directory that holds all persistent state: the database and its backups (ADR 0009).
 *                          It is {@code /config} in the container.
 * @param encryptionKeyFile the value of {@code WARDROOM_ENCRYPTION_KEY_FILE}: a file outside the data directory that
 *                          holds the key for the stored NNTP password (ADR 0025). Empty or absent when not set.
 */
@Validated
@ConfigurationProperties("wardroom")
public record WardroomProperties(@NotNull Path dataDir, String encryptionKeyFile) {

    public Path databaseFile() {
        return dataDir.resolve("wardroom.db");
    }

    public Path backupDirectory() {
        return dataDir.resolve("backups");
    }

    /** @return the key file the owner keeps outside the data directory, if one is configured */
    public Optional<Path> externalEncryptionKeyFile() {
        return encryptionKeyFile == null || encryptionKeyFile.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(encryptionKeyFile.strip()));
    }
}
