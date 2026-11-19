package io.github.stekhub.wardroom.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The startup sequence of ADR 0004 and the backup retention of ADR 0028, against a real SQLite database. */
class DatabaseMigratorTest {

    private static final String V1 = "classpath:db/testmigration/v1";
    private static final String V2 = "classpath:db/testmigration/v2";
    private static final String V2_BROKEN = "classpath:db/testmigration/v2broken";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-04T05:06:07.089Z"), ZoneOffset.UTC);
    private static final String NEW_BACKUP = "wardroom-v1-20260304T050607089Z.db";

    @TempDir
    Path dataDir;

    private Path backups;
    private DataSource dataSource;
    private DatabaseMigrator migrator;

    @BeforeEach
    void setUp() {
        backups = dataDir.resolve("backups");
        dataSource = DatabaseConfiguration.sqliteDataSource(dataDir.resolve("wardroom.db").toString());
        migrator = new DatabaseMigrator(backups, CLOCK);
    }

    @Test
    void newDatabaseIsMigratedWithoutBackup() {
        migrator.migrate(flyway(V1, V2));

        assertThat(schemaVersion()).isEqualTo("2");
        assertThat(backups).doesNotExist();
    }

    @Test
    void currentDatabaseIsLeftAlone() {
        migrator.migrate(flyway(V1));
        migrator.migrate(flyway(V1));

        assertThat(schemaVersion()).isEqualTo("1");
        assertThat(backups).doesNotExist();
    }

    @Test
    void existingDatabaseIsBackedUpBeforePendingMigrations() throws SQLException, IOException {
        migrator.migrate(flyway(V1));
        execute(dataSource, "INSERT INTO note (text) VALUES ('written before the update')");

        migrator.migrate(flyway(V1, V2));

        assertThat(schemaVersion()).isEqualTo("2");
        assertThat(backupFiles()).containsExactly(NEW_BACKUP);
        DataSource backup = DatabaseConfiguration.sqliteDataSource(backups.resolve(NEW_BACKUP).toString());
        assertThat(queryString(backup, "SELECT text FROM note")).isEqualTo("written before the update");
        assertThat(queryString(backup, "SELECT max(version) FROM flyway_schema_history")).isEqualTo("1");
    }

    @Test
    void onlyTheTwoNewestBackupsAreKept() throws IOException {
        migrator.migrate(flyway(V1));
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("wardroom-v8-20250101T000000000Z.db"), "oldest");
        Files.writeString(backups.resolve("wardroom-v10-20250601T000000000Z.db"), "older");
        Files.writeString(backups.resolve("wardroom-v9-20250301T000000000Z.db"), "old");
        Files.writeString(backups.resolve("my-own-copy.db"), "not written by Wardroom");

        migrator.migrate(flyway(V1, V2));

        assertThat(backupFiles()).containsExactlyInAnyOrder(NEW_BACKUP, "wardroom-v10-20250601T000000000Z.db",
                "my-own-copy.db");
    }

    @Test
    void failedBackupAbortsBeforeAnyMigration() throws IOException {
        migrator.migrate(flyway(V1));
        Files.writeString(backups, "a file where the backup directory should be");

        assertThatThrownBy(() -> migrator.migrate(flyway(V1, V2)))
                .isInstanceOf(DatabaseStartupException.class)
                .hasMessageContaining("could not be written")
                .hasMessageContaining("No migration was applied");
        assertThat(schemaVersion()).isEqualTo("1");
    }

    @Test
    void failedMigrationAbortsAndKeepsTheBackup() throws IOException {
        migrator.migrate(flyway(V1));
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("wardroom-v8-20250101T000000000Z.db"), "oldest");
        Files.writeString(backups.resolve("wardroom-v9-20250301T000000000Z.db"), "old");

        assertThatThrownBy(() -> migrator.migrate(flyway(V1, V2_BROKEN))).isInstanceOf(FlywayException.class);

        assertThat(schemaVersion()).isEqualTo("1");
        assertThat(backupFiles()).as("no backup is deleted when the migration failed").containsExactlyInAnyOrder(
                NEW_BACKUP, "wardroom-v8-20250101T000000000Z.db", "wardroom-v9-20250301T000000000Z.db");
    }

    @Test
    void databaseOfANewerVersionIsRefused() {
        migrator.migrate(flyway(V1, V2));

        assertThatThrownBy(() -> migrator.migrate(flyway(V1)))
                .isInstanceOf(DatabaseStartupException.class)
                .hasMessageContaining("newer version of Wardroom")
                .hasMessageContaining("schema version 2");
        assertThat(schemaVersion()).isEqualTo("2");
        assertThat(backups).doesNotExist();
    }

    private Flyway flyway(String... locations) {
        return Flyway.configure().dataSource(dataSource).locations(locations).load();
    }

    private String schemaVersion() {
        return queryString(dataSource, "SELECT max(version) FROM flyway_schema_history WHERE success = 1");
    }

    private List<String> backupFiles() throws IOException {
        try (Stream<Path> files = Files.list(backups)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryString(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
