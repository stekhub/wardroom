package io.github.stekhub.wardroom.platform.database;

import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.platform.WardroomProperties;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(DatabaseStartupTest.RepositoryThatReadsWhenCreated.class)
class DatabaseStartupTest {

    @Autowired
    RepositoryThatReadsWhenCreated repository;

    @Autowired
    DSLContext dsl;

    @Autowired
    WardroomProperties properties;

    /**
     * ADR 0004: Flyway runs before any repository code accesses the database. This bean queries a migrated table in
     * its constructor; the application context would not start if the bean were created before the migrations ran.
     */
    @Test
    void migrationsRunBeforeRepositoriesAreCreated() {
        assertThat(repository.usersAtCreation).isZero();
    }

    @Test
    void connectionsUseTheSqliteSettingsOfAdr0003() {
        assertThat(dsl.fetchValue("PRAGMA foreign_keys")).hasToString("1");
        assertThat(dsl.fetchValue("PRAGMA journal_mode")).hasToString("wal");
        assertThat(dsl.fetchValue("PRAGMA busy_timeout"))
                .hasToString(String.valueOf(DatabaseConfiguration.BUSY_TIMEOUT_MILLIS));
    }

    @Test
    void databaseIsCreatedInTheDataDirectory() {
        assertThat(properties.databaseFile()).isRegularFile();
        assertThat(properties.backupDirectory()).as("a new database needs no backup").doesNotExist();
    }

    @TestComponent
    static class RepositoryThatReadsWhenCreated {

        final int usersAtCreation;

        RepositoryThatReadsWhenCreated(DSLContext dsl) {
            usersAtCreation = dsl.fetchCount(WARDROOM_USER);
        }
    }
}
