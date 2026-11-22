package io.github.stekhub.wardroom.platform.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The database part of the startup sequence (ADR 0004, ADR 0028, ADR 0032).
 *
 * <ol>
 *   <li>A database written by a newer version of Wardroom is refused.</li>
 *   <li>If migrations are pending on an existing database, it is first copied with {@code VACUUM INTO}. The copy is
 *       named after the version of Wardroom that used the database last. If the copy fails, no migration is
 *       applied.</li>
 *   <li>The pending migrations are applied. A failing migration aborts the startup; the backup stays.</li>
 *   <li>After a successful migration only the newest backups are kept.</li>
 *   <li>The version that is starting is stored in the database, for the name of the next backup.</li>
 * </ol>
 */
@Slf4j
class DatabaseMigrator {

    static final int BACKUPS_KEPT = 2;

    /** The key in {@code app_setting} under which the version that used the database last is stored (ADR 0032). */
    static final String VERSION_KEY = "app.version";

    /** Stands in the backup name for a database that has no stored version. */
    static final String UNKNOWN_VERSION = "unknown";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * A backup written by Wardroom: {@code wardroom-0.9.0-schema7-{timestamp}.db}, or {@code wardroom-v7-{timestamp}.db}
     * as it was named before ADR 0032. Both count for the retention.
     */
    private static final Pattern BACKUP_FILE =
            Pattern.compile("wardroom-(?:.+-schema[^-]+|v[^-]+)-(\\d{8}T\\d{9}Z)\\.db");

    /** The stored version is data; anything but these characters is replaced before it becomes part of a name. */
    private static final Pattern NOT_ALLOWED_IN_FILE_NAME = Pattern.compile("[^A-Za-z0-9.+_-]");

    private final Path backupDirectory;
    private final String applicationVersion;
    private final Clock clock;

    DatabaseMigrator(Path backupDirectory, String applicationVersion) {
        this(backupDirectory, applicationVersion, Clock.systemUTC());
    }

    DatabaseMigrator(Path backupDirectory, String applicationVersion, Clock clock) {
        this.backupDirectory = backupDirectory;
        this.applicationVersion = applicationVersion;
        this.clock = clock;
    }

    void migrate(Flyway flyway) {
        MigrationInfoService info = flyway.info();
        refuseNewerDatabase(info);

        DataSource dataSource = flyway.getConfiguration().getDataSource();
        MigrationInfo current = info.current();
        int pending = info.pending().length;
        boolean backedUp = false;
        if (pending > 0 && current != null) {
            backUp(dataSource, current.getVersion().getVersion());
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
        storeVersion(dataSource);
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

    private void backUp(DataSource dataSource, String schemaVersion) {
        String storedVersion = UNKNOWN_VERSION;
        Path backup = backupDirectory;
        try {
            storedVersion = storedVersion(dataSource);
            backup = backupDirectory.resolve("wardroom-" + fileNamePart(storedVersion) + "-schema" + schemaVersion
                    + "-" + TIMESTAMP.format(clock.instant()) + ".db");
            Files.createDirectories(backupDirectory);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'");
            }
        } catch (IOException | SQLException e) {
            throw new DatabaseStartupException("The database backup " + backup + " could not be written: "
                    + e.getMessage() + ". No migration was applied.", e);
        }
        log.info("Database backed up to {} before applying migrations; the data comes from Wardroom {}, "
                + "Wardroom {} is starting", backup, fileNamePart(storedVersion), applicationVersion);
    }

    /** Read with plain SQL before the migrations, as ADR 0004 requires. */
    private static String storedVersion(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasSettingsTable(connection)) {
                return UNKNOWN_VERSION;
            }
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT value FROM app_setting WHERE key = ?")) {
                statement.setString(1, VERSION_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : UNKNOWN_VERSION;
                }
            }
        }
    }

    private void storeVersion(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasSettingsTable(connection)) {
                log.warn("The version could not be stored: the database has no table app_setting");
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO app_setting (key, value) "
                    + "VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
                statement.setString(1, VERSION_KEY);
                statement.setString(2, applicationVersion);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            // The database is migrated; only the name of the next backup suffers.
            log.warn("The version could not be stored in the database: {}", e.toString());
        }
    }

    private static boolean hasSettingsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'app_setting'")) {
            return result.next();
        }
    }

    private static String fileNamePart(String version) {
        String allowed = NOT_ALLOWED_IN_FILE_NAME.matcher(version).replaceAll("_");
        return allowed.isEmpty() ? UNKNOWN_VERSION : allowed;
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
