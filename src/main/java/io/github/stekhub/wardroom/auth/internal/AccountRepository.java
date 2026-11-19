package io.github.stekhub.wardroom.auth.internal;

import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Database access for the account and for the stored sessions. */
@Repository
@RequiredArgsConstructor
class AccountRepository {

    private final DSLContext database;

    boolean accountExists() {
        return database.fetchExists(WARDROOM_USER);
    }

    boolean accountExists(String username) {
        return database.fetchExists(WARDROOM_USER, WARDROOM_USER.USERNAME.eq(username));
    }

    /** @return the password hash of the account with exactly this username */
    Optional<String> findPasswordHash(String username) {
        return database.select(WARDROOM_USER.PASSWORD_HASH)
                .from(WARDROOM_USER)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .fetchOptional(WARDROOM_USER.PASSWORD_HASH);
    }

    void insertAccount(String username, String passwordHash) {
        database.insertInto(WARDROOM_USER)
                .set(WARDROOM_USER.USERNAME, username)
                .set(WARDROOM_USER.PASSWORD_HASH, passwordHash)
                .execute();
    }

    void updatePasswordHash(String username, String passwordHash) {
        database.update(WARDROOM_USER)
                .set(WARDROOM_USER.PASSWORD_HASH, passwordHash)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .execute();
    }

    /** The attributes of a session are deleted with it by the database. */
    void deleteAllSessions() {
        database.deleteFrom(SPRING_SESSION).execute();
    }

    void deleteSessionsExcept(String sessionId) {
        database.deleteFrom(SPRING_SESSION).where(SPRING_SESSION.SESSION_ID.ne(sessionId)).execute();
    }
}
