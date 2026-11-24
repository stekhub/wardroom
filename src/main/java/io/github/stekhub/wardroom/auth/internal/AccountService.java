package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.auth.AccountCreationCleanup;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.InvalidCredentialsException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.LoginThrottledException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.SecondFactorFailedException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.SetupUnavailableException;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Creating the account, checking a login and changing the password (spec 001, spec 011, spec 012). */
@Slf4j
@Service
@RequiredArgsConstructor
class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordVerifier passwordVerifier;
    private final LoginThrottle loginThrottle;
    private final TwoFactorService twoFactorService;
    private final ObjectProvider<AccountCreationCleanup> accountCreationCleanups;
    private final TransactionOperations transactionOperations;

    /**
     * The result of a correct password.
     *
     * @param username             the username of the account
     * @param secondFactorRequired whether the login is complete only after a code (spec 012)
     */
    record CheckedPassword(String username, boolean secondFactorRequired) {
    }

    boolean setupAvailable() {
        return !accountRepository.accountExists();
    }

    /**
     * Creates the account, with the cleanup of ADR 0017, in one transaction.
     *
     * @return the username as it was stored
     */
    String createAccount(String username, String password) {
        if (!setupAvailable()) {
            throw new SetupUnavailableException();
        }
        String validUsername = AccountRules.validUsername("username", username);
        AccountRules.requireValidPassword("password", password);
        // Hashing takes a moment, so it happens before the transaction and not while the database is locked.
        String passwordHash = passwordEncoder.encode(password);

        transactionOperations.executeWithoutResult(status -> {
            // The transaction starts with a write. SQLite then locks the database for other writers, so two
            // simultaneous setup requests run one after the other and the second one finds the account.
            accountRepository.deleteAllSessions();
            accountRepository.deleteRecoveryCodes();
            accountCreationCleanups.orderedStream().forEach(AccountCreationCleanup::removeSensitiveData);
            if (accountRepository.accountExists()) {
                throw new SetupUnavailableException();
            }
            accountRepository.insertAccount(validUsername, passwordHash);
        });
        log.info("The account was created");
        return validUsername;
    }

    /**
     * Checks username and password of a login. Without a second factor the login is complete.
     *
     * @throws LoginThrottledException     if the attempt has to wait; the password is not checked then
     * @throws InvalidCredentialsException if username or password is wrong
     */
    CheckedPassword checkPassword(String username, String password, ClientAddress clientAddress) {
        // Logins are checked one after the other, so parallel attempts cannot get around the throttling.
        return loginThrottle.oneAtATime(() -> {
            rejectDuringTheWaitingTime();
            String normalizedUsername = AccountRules.normalizedUsername(username);
            if (!passwordVerifier.matches(normalizedUsername, password)) {
                recordFailure(clientAddress);
                throw new InvalidCredentialsException();
            }
            if (accountRepository.twoFactorEnabled(normalizedUsername)) {
                // The counter stays: only a correct code resets it. Otherwise the codes could be tried out by
                // entering the right password between the attempts.
                return new CheckedPassword(normalizedUsername, true);
            }
            recordSuccess(clientAddress);
            return new CheckedPassword(normalizedUsername, false);
        });
    }

    /**
     * Checks the code of the second step of a login.
     *
     * @param username the account whose password was checked before
     * @throws LoginThrottledException     if the attempt has to wait; the code is not checked then
     * @throws SecondFactorFailedException if the code is wrong
     */
    void checkSecondFactor(String username, String code, ClientAddress clientAddress) {
        loginThrottle.oneAtATime(() -> {
            rejectDuringTheWaitingTime();
            if (!accountRepository.twoFactorEnabled(username) || !twoFactorService.acceptCode(username, code)) {
                recordFailure(clientAddress);
                throw new SecondFactorFailedException("The code is wrong.");
            }
            recordSuccess(clientAddress);
            return null;
        });
    }

    /**
     * Changes the password and ends all sessions, including the one that changed it. A copy of a session ID that
     * somebody else holds is worthless afterwards.
     */
    void changePassword(String username, String currentPassword, String newPassword) {
        if (!passwordVerifier.matches(username, currentPassword)) {
            throw new FieldValidationException("currentPassword", "The current password is wrong.");
        }
        AccountRules.requireValidPassword("newPassword", newPassword);
        String passwordHash = passwordEncoder.encode(newPassword);

        transactionOperations.executeWithoutResult(status -> {
            accountRepository.updatePasswordHash(username, passwordHash);
            accountRepository.deleteAllSessions();
        });
        log.info("The password was changed; all sessions were ended");
    }

    /** Attempts during a waiting time are not logged one by one, so an attacker cannot fill the log. */
    private void rejectDuringTheWaitingTime() {
        Duration remainingWait = loginThrottle.remainingWait();
        if (remainingWait.isPositive()) {
            throw new LoginThrottledException(remainingWait);
        }
    }

    /** The entered username and password are never logged. */
    private void recordFailure(ClientAddress clientAddress) {
        log.warn("Failed login from {}", clientAddress.forLog());
        Duration startedWait = loginThrottle.recordFailure();
        if (startedWait.isPositive()) {
            long seconds = startedWait.toSeconds();
            log.warn("Too many failed logins in a row; the next login has to wait {} {}", seconds,
                    seconds == 1 ? "second" : "seconds");
        }
    }

    private void recordSuccess(ClientAddress clientAddress) {
        loginThrottle.recordSuccess();
        log.info("Login from {}", clientAddress.forLog());
    }
}
