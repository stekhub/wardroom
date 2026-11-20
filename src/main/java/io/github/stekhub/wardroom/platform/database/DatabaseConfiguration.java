package io.github.stekhub.wardroom.platform.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.stekhub.wardroom.platform.WardroomProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {

    /** How long a connection waits for a lock held by another connection before it fails. */
    static final int BUSY_TIMEOUT_MILLIS = 5_000;

    /** SQLite allows one writer at a time; the pool only has to serve a few parallel readers. */
    static final int POOL_SIZE = 4;

    @Bean
    HikariDataSource dataSource(WardroomProperties properties) {
        try {
            Files.createDirectories(properties.dataDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the data directory " + properties.dataDir(), e);
        }
        HikariConfig pool = new HikariConfig();
        pool.setPoolName("wardroom-db");
        pool.setMaximumPoolSize(POOL_SIZE);
        pool.setDataSource(sqliteDataSource(properties.databaseFile().toString()));
        return new HikariDataSource(pool);
    }

    /**
     * Every connection gets the settings of ADR 0003: foreign keys on, WAL journal mode and a busy timeout.
     *
     * <p>A transaction takes the write lock when it begins, and waits for it up to the busy timeout. Without this,
     * a transaction that reads first and writes later fails as soon as another connection has written in between,
     * and SQLite does not wait in that case.
     */
    static SQLiteDataSource sqliteDataSource(String databaseFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databaseFile);
        return dataSource;
    }

    /** Replaces Flyway's plain migration with the startup sequence of ADR 0004. */
    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(WardroomProperties properties) {
        DatabaseMigrator migrator = new DatabaseMigrator(properties.backupDirectory());
        return migrator::migrate;
    }
}
