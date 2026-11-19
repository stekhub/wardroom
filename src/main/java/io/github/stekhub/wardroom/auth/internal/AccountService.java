package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.auth.AccountCreationCleanup;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.InvalidCredentialsException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.LoginThrottledException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.SetupUnavailableException;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Creating the account, checking a login and changing the password (spec 001). */
@Slf4j
@Service
class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle loginThrottle;
    private final ObjectProvider<AccountCreationCleanup> accountCreationCleanups;
    private final TransactionOperations transactionOperations;

    /** A hash no password matches. It is checked when the username is unknown, so that login always takes as long. */
    private final String unusedPasswordHash;

    /** Logins are checked one after the other, so parallel attempts cannot get around the throttling. */
    private final ReentrantLock loginLock = new ReentrantLock();

    AccountService(AccountRepository accountRepository, PasswordEncoder passwordEncoder, LoginThrottle loginThrottle,
            ObjectProvider<AccountCreationCleanup> accountCreationCleanups,
            TransactionOperations transactionOperations) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginThrottle = loginThrottle;
        this.accountCreationCleanups = accountCreationCleanups;
        this.transactionOperations = transactionOperations;
        this.unusedPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
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
     * Checks a login.
     *
     * @return the username of the account
     * @throws LoginThrottledException     if the attempt has to wait; the password is not checked then
     * @throws InvalidCredentialsException if username or password is wrong
     */
    String login(String username, String password) {
        loginLock.lock();
        try {
            Duration remainingWait = loginThrottle.remainingWait();
            if (remainingWait.isPositive()) {
                throw new LoginThrottledException(remainingWait);
            }
            String normalizedUsername = AccountRules.normalizedUsername(username);
            if (!credentialsMatch(normalizedUsername, password)) {
                loginThrottle.recordFailure();
                throw new InvalidCredentialsException();
            }
            loginThrottle.recordSuccess();
            return normalizedUsername;
        } finally {
            loginLock.unlock();
        }
    }

    /**
     * Changes the password and ends all sessions, including the one that changed it. A copy of a session ID that
     * somebody else holds is worthless afterwards.
     */
    void changePassword(String username, String currentPassword, String newPassword) {
        if (!credentialsMatch(username, currentPassword)) {
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

    /** Always computes one password hash, also for an unknown username or a password that is too long to be valid. */
    private boolean credentialsMatch(String username, String password) {
        Optional<String> storedPasswordHash = accountRepository.findPasswordHash(username);
        boolean passwordCanBeValid = AccountRules.hasValidPasswordLength(password);
        boolean matches = passwordEncoder.matches(passwordCanBeValid ? password : "",
                storedPasswordHash.orElse(unusedPasswordHash));
        return storedPasswordHash.isPresent() && passwordCanBeValid && matches;
    }
}
