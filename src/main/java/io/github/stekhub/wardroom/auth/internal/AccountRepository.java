package io.github.stekhub.wardroom.auth.internal;

import static io.github.stekhub.wardroom.jooq.Tables.RECOVERY_CODE;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Database access for the account, its second factor and the stored sessions. */
@Repository
@RequiredArgsConstructor
class AccountRepository implements LoginThrottleStore {

    private final DSLContext database;

    /**
     * The second factor as it is stored with the account (spec 012).
     *
     * @param secretEncrypted the encrypted secret, or {@code null} when no setup was started
     * @param enabled         whether a login needs a code
     * @param lastUsedPeriod  the 30-second period of the last accepted code, or {@code null}
     */
    record StoredTwoFactor(byte[] secretEncrypted, boolean enabled, Long lastUsedPeriod) {
    }

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

    // Login throttling: there is one account, and its row holds the state.

    @Override
    public Optional<LoginThrottleState> findLoginThrottleState() {
        return database.select(WARDROOM_USER.FAILED_LOGINS, WARDROOM_USER.LOGIN_BLOCKED_UNTIL)
                .from(WARDROOM_USER)
                .limit(1)
                .fetchOptional(row -> new LoginThrottleState(row.value1(),
                        row.value2() == null ? null : Instant.ofEpochMilli(row.value2())));
    }

    @Override
    public boolean storeLoginThrottleState(LoginThrottleState state) {
        return database.update(WARDROOM_USER)
                .set(WARDROOM_USER.FAILED_LOGINS, state.failedLogins())
                .set(WARDROOM_USER.LOGIN_BLOCKED_UNTIL,
                        state.blockedUntil() == null ? null : state.blockedUntil().toEpochMilli())
                .execute() > 0;
    }

    // Second factor

    Optional<StoredTwoFactor> findTwoFactor(String username) {
        return database.select(WARDROOM_USER.TOTP_SECRET, WARDROOM_USER.TOTP_ENABLED,
                        WARDROOM_USER.TOTP_LAST_USED_PERIOD)
                .from(WARDROOM_USER)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .fetchOptional(row -> new StoredTwoFactor(row.value1(), row.value2(), row.value3()));
    }

    boolean twoFactorEnabled(String username) {
        return database.fetchExists(WARDROOM_USER,
                WARDROOM_USER.USERNAME.eq(username).and(WARDROOM_USER.TOTP_ENABLED.isTrue()));
    }

    boolean twoFactorSecretStored() {
        return database.fetchExists(WARDROOM_USER, WARDROOM_USER.TOTP_SECRET.isNotNull());
    }

    /** Stores the secret of a setup that was started. The second factor stays off until a code turns it on. */
    void storePendingTwoFactorSecret(String username, byte[] secretEncrypted) {
        database.update(WARDROOM_USER)
                .set(WARDROOM_USER.TOTP_SECRET, secretEncrypted)
                .set(WARDROOM_USER.TOTP_ENABLED, false)
                .setNull(WARDROOM_USER.TOTP_LAST_USED_PERIOD)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .execute();
    }

    void enableTwoFactor(String username) {
        database.update(WARDROOM_USER)
                .set(WARDROOM_USER.TOTP_ENABLED, true)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .execute();
    }

    void removeTwoFactor(String username) {
        database.update(WARDROOM_USER)
                .setNull(WARDROOM_USER.TOTP_SECRET)
                .set(WARDROOM_USER.TOTP_ENABLED, false)
                .setNull(WARDROOM_USER.TOTP_LAST_USED_PERIOD)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .execute();
    }

    /**
     * Remembers the period of a code that is being accepted.
     *
     * @return {@code false} when a code of this period or a later one was accepted before
     */
    boolean markTwoFactorPeriodUsed(String username, long period) {
        return database.update(WARDROOM_USER)
                .set(WARDROOM_USER.TOTP_LAST_USED_PERIOD, period)
                .where(WARDROOM_USER.USERNAME.eq(username))
                .and(WARDROOM_USER.TOTP_LAST_USED_PERIOD.isNull().or(WARDROOM_USER.TOTP_LAST_USED_PERIOD.lt(period)))
                .execute() > 0;
    }

    int countRecoveryCodes() {
        return database.fetchCount(RECOVERY_CODE);
    }

    void replaceRecoveryCodes(List<String> codeHashes) {
        database.deleteFrom(RECOVERY_CODE).execute();
        for (String codeHash : codeHashes) {
            database.insertInto(RECOVERY_CODE).set(RECOVERY_CODE.CODE_HASH, codeHash).execute();
        }
    }

    void deleteRecoveryCodes() {
        database.deleteFrom(RECOVERY_CODE).execute();
    }

    /** @return whether the code existed; it is deleted, because a recovery code works once */
    boolean useRecoveryCode(String codeHash) {
        return database.deleteFrom(RECOVERY_CODE).where(RECOVERY_CODE.CODE_HASH.eq(codeHash)).execute() > 0;
    }
}
