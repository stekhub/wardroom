package io.github.stekhub.wardroom.platform.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The database part of the startup sequence (ADR 0004, ADR 0028).
 *
 * <ol>
 *   <li>A database written by a newer version of Wardroom is refused.</li>
 *   <li>If migrations are pending on an existing database, it is first copied with {@code VACUUM INTO}. If the copy
 *       fails, no migration is applied.</li>
 *   <li>The pending migrations are applied. A failing migration aborts the startup; the backup stays.</li>
 *   <li>After a successful migration only the newest backups are kept.</li>
 * </ol>
 */
@Slf4j
class DatabaseMigrator {

    static final int BACKUPS_KEPT = 2;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern BACKUP_FILE = Pattern.compile("wardroom-v[^-]+-(\\d{8}T\\d{9}Z)\\.db");

    private final Path backupDirectory;
    private final Clock clock;

    DatabaseMigrator(Path backupDirectory) {
        this(backupDirectory, Clock.systemUTC());
    }

    DatabaseMigrator(Path backupDirectory, Clock clock) {
        this.backupDirectory = backupDirectory;
        this.clock = clock;
    }

    void migrate(Flyway flyway) {
        MigrationInfoService info = flyway.info();
        refuseNewerDatabase(info);

        MigrationInfo current = info.current();
        int pending = info.pending().length;
        boolean backedUp = false;
        if (pending > 0 && current != null) {
            backUp(flyway, current.getVersion().getVersion());
            backedUp = true;
        }

        MigrateResult result = flyway.migrate();
        if (result.migrationsExecuted > 0) {
            log.info("Applied {} database migration(s); schema version is now {}", result.migrationsExecuted,
                    result.targetSchemaVersion);
        }
        if (backedUp) {
            deleteOldBackups();
        }
    }

    private void refuseNewerDatabase(MigrationInfoService info) {
        List<String> unknown = Arrays.stream(info.all())
                .filter(migration -> migration.getState() == MigrationState.FUTURE_SUCCESS
                        || migration.getState() == MigrationState.FUTURE_FAILED)
                .map(migration -> migration.getVersion().getVersion())
                .toList();
        if (!unknown.isEmpty()) {
            throw new DatabaseStartupException("The database was written by a newer version of Wardroom (schema "
                    + "version " + unknown.getLast() + " is unknown to this version). Start the newer version again, "
                    + "or restore the matching backup from " + backupDirectory + ".");
        }
    }

    private void backUp(Flyway flyway, String schemaVersion) {
        Path backup = backupDirectory.resolve(
                "wardroom-v" + schemaVersion + "-" + TIMESTAMP.format(clock.instant()) + ".db");
        try {
            Files.createDirectories(backupDirectory);
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'");
            }
        } catch (IOException | SQLException e) {
            throw new DatabaseStartupException("The database backup " + backup + " could not be written: "
                    + e.getMessage() + ". No migration was applied.", e);
        }
        log.info("Database backed up to {} before applying migrations", backup);
    }

    private void deleteOldBackups() {
        try (Stream<Path> files = Files.list(backupDirectory)) {
            List<Path> newestFirst = files
                    .filter(file -> BACKUP_FILE.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(DatabaseMigrator::timestampOf).reversed())
                    .toList();
            for (Path old : newestFirst.subList(Math.min(BACKUPS_KEPT, newestFirst.size()), newestFirst.size())) {
                Files.delete(old);
                log.info("Deleted old database backup {}", old);
            }
        } catch (IOException e) {
            // The migration already succeeded; a leftover backup must not stop the application.
            log.warn("Old database backups in {} could not be deleted: {}", backupDirectory, e.toString());
        }
    }

    private static String timestampOf(Path backup) {
        Matcher matcher = BACKUP_FILE.matcher(backup.getFileName().toString());
        return matcher.matches() ? matcher.group(1) : "";
    }
}
