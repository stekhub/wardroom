package io.github.stekhub.wardroom.platform.database;

/** The database cannot be brought to the current schema; the application must not start (ADR 0004). */
class DatabaseStartupException extends RuntimeException {

    DatabaseStartupException(String message) {
        super(message);
    }

    DatabaseStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
