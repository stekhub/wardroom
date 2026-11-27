package io.github.stekhub.wardroom.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The groups that are ignored from the start (spec 022), with the real migrations and a real SQLite database. */
class DefaultIgnoredGroupsMigrationTest {

    private static final String BEFORE_THE_DEFAULT_RULES = "16";

    @TempDir
    Path dataDir;

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = DatabaseConfiguration.sqliteDataSource(dataDir.resolve("wardroom.db").toString());
    }

    @Test
    void aNewInstallationIgnoresTheNoisyGroups() throws SQLException {
        migrateTo("latest");

        assertThat(ignoredGroups()).containsExactlyInAnyOrder("misc.phone.mobile.android", "alt.dvdnordic.org",
                "free.usenet", "free.pt", "a2i.b", "zzz.3", "de.comp.os.ms-windows", "de.alt.sources.amiga");
        assertThat(queryStrings("SELECT DISTINCT label = value AND newsgroup_id IS NULL AND created_at > 0"
                + " FROM ignore_rule")).as("the rules look like the ones the owner creates").containsExactly("1");
    }

    @Test
    void anExistingInstallationKeepsASubscribedGroupAndARuleItAlreadyHas() throws SQLException {
        migrateTo(BEFORE_THE_DEFAULT_RULES);
        // The server of the group is not needed here.
        execute("PRAGMA foreign_keys = OFF",
                "INSERT INTO newsgroup (id, server_id, name, posting_status, low_watermark, high_watermark)"
                        + " VALUES (7, 1, 'free.usenet', 'ALLOWED', 1, 100)",
                "INSERT INTO subscription (newsgroup_id) VALUES (7)",
                "INSERT INTO ignore_rule (kind, value, label, created_at) VALUES ('GROUP', 'zzz.3', 'zzz.3', 5)");

        migrateTo("latest");

        assertThat(ignoredGroups()).containsExactlyInAnyOrder("misc.phone.mobile.android", "alt.dvdnordic.org",
                "free.pt", "a2i.b", "zzz.3", "de.comp.os.ms-windows", "de.alt.sources.amiga");
        assertThat(queryStrings("SELECT created_at FROM ignore_rule WHERE value = 'zzz.3'")).containsExactly("5");
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(version).load()
                .migrate();
    }

    private List<String> ignoredGroups() throws SQLException {
        return queryStrings("SELECT value FROM ignore_rule WHERE kind = 'GROUP'");
    }

    /** Runs the statements on one connection, so that a setting of the connection holds for the next ones. */
    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
