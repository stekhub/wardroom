package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.auth.internal.AccountRepository.StoredTwoFactor;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.LoginThrottledException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.TwoFactorStateException;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.RecoveryCodesResponse;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorSetup;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorStatus;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.platform.SecretCipher;
import io.github.stekhub.wardroom.platform.SecretDecryptionException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The second factor of the owner: codes from an authenticator app and one-time recovery codes (spec 012, ADR 0035).
 *
 * <p>The secret, the codes and the recovery codes are never logged.
 *
 * <p>A wrong code in the settings counts for the login throttling like a failed login. Without this, somebody with
 * a session and the password could try out all codes and turn the second factor off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class TwoFactorService {

    /** The purpose of the stored secret in {@link SecretCipher}. */
    static final String SECRET_PURPOSE = "totp-secret";

    private static final int SECRET_LENGTH_BYTES = 20;
    private static final String ISSUER = "Wardroom";
    private static final String WRONG_CODE = "The code is wrong.";

    private final AccountRepository accountRepository;
    private final PasswordVerifier passwordVerifier;
    private final SecretCipher secretCipher;
    private final LoginThrottle loginThrottle;
    private final InstantSource clock;
    private final TransactionOperations transactionOperations;
    private final SecureRandom random = new SecureRandom();

    TwoFactorStatus status(String username) {
        boolean enabled = accountRepository.twoFactorEnabled(username);
        return new TwoFactorStatus(enabled, enabled ? accountRepository.countRecoveryCodes() : 0);
    }

    /**
     * Starts turning the second factor on: creates a new secret and stores it, with the second factor still off.
     * Every start creates a new secret, because a QR code that was shown before may have been seen or saved.
     */
    TwoFactorSetup startSetup(String username, String currentPassword) {
        requirePassword(username, currentPassword);
        if (accountRepository.twoFactorEnabled(username)) {
            throw new TwoFactorStateException("Two-factor authentication is already on.");
        }
        byte[] secretBytes = new byte[SECRET_LENGTH_BYTES];
        random.nextBytes(secretBytes);
        String secret = Base32.encode(secretBytes);
        accountRepository.storePendingTwoFactorSecret(username, secretCipher.encrypt(SECRET_PURPOSE, secret));
        return new TwoFactorSetup(otpauthUri(username, secret), secret);
    }

    /** Turns the second factor on with a code from the app, and creates the recovery codes. */
    RecoveryCodesResponse enable(String username, String code) {
        StoredTwoFactor twoFactor = accountRepository.findTwoFactor(username).orElseThrow();
        if (twoFactor.enabled()) {
            throw new TwoFactorStateException("Two-factor authentication is already on.");
        }
        if (twoFactor.secretEncrypted() == null) {
            throw new TwoFactorStateException("The setup of two-factor authentication was not started.");
        }
        requireCode(username, () -> acceptAppCode(username, code));
        List<String> recoveryCodes = RecoveryCodes.generate(random);
        transactionOperations.executeWithoutResult(status -> {
            accountRepository.enableTwoFactor(username);
            accountRepository.replaceRecoveryCodes(recoveryCodes.stream().map(RecoveryCodes::hash).toList());
        });
        log.info("Two-factor authentication was turned on");
        return new RecoveryCodesResponse(recoveryCodes);
    }

    /** Creates new recovery codes; the old ones stop working. */
    RecoveryCodesResponse createRecoveryCodes(String username, String currentPassword, String code) {
        requirePasswordAndCode(username, currentPassword, code);
        List<String> recoveryCodes = RecoveryCodes.generate(random);
        transactionOperations.executeWithoutResult(status -> accountRepository.replaceRecoveryCodes(
                recoveryCodes.stream().map(RecoveryCodes::hash).toList()));
        log.info("New recovery codes were created");
        return new RecoveryCodesResponse(recoveryCodes);
    }

    /** Turns the second factor off and deletes the secret and the recovery codes. */
    void disable(String username, String currentPassword, String code) {
        requirePasswordAndCode(username, currentPassword, code);
        transactionOperations.executeWithoutResult(status -> {
            accountRepository.removeTwoFactor(username);
            accountRepository.deleteRecoveryCodes();
        });
        log.info("Two-factor authentication was turned off");
    }

    /**
     * Checks a code from the app or a recovery code. An accepted code cannot be used again.
     *
     * @return whether the code was accepted
     */
    boolean acceptCode(String username, String code) {
        String entered = code == null ? "" : code.strip();
        if (entered.length() == Totp.DIGITS && entered.chars().allMatch(character -> character >= '0'
                && character <= '9')) {
            return acceptAppCode(username, entered);
        }
        String recoveryCode = RecoveryCodes.normalized(entered);
        return recoveryCode.length() == RecoveryCodes.CODE_LENGTH
                && accountRepository.useRecoveryCode(RecoveryCodes.hash(recoveryCode));
    }

    /**
     * A code is accepted for the current 30-second period and one period before and after, because the clocks of
     * the phone and of Wardroom differ and typing takes time.
     */
    private boolean acceptAppCode(String username, String code) {
        String entered = code == null ? "" : code.strip();
        Optional<StoredTwoFactor> twoFactor = accountRepository.findTwoFactor(username);
        if (twoFactor.isEmpty() || twoFactor.get().secretEncrypted() == null) {
            return false;
        }
        byte[] secret;
        try {
            secret = Base32.decode(secretCipher.decrypt(SECRET_PURPOSE, twoFactor.get().secretEncrypted()));
        } catch (SecretDecryptionException | IllegalArgumentException e) {
            log.warn("The secret of two-factor authentication cannot be read with the key file, so codes from the "
                    + "authenticator app cannot be checked. Log in with a recovery code, or reset the password as "
                    + "the user guide describes.");
            return false;
        }
        long currentPeriod = Totp.period(clock.instant());
        for (long period = currentPeriod - 1; period <= currentPeriod + 1; period++) {
            if (sameText(Totp.code(secret, period), entered)) {
                return accountRepository.markTwoFactorPeriodUsed(username, period);
            }
        }
        return false;
    }

    private void requirePasswordAndCode(String username, String currentPassword, String code) {
        requirePassword(username, currentPassword);
        if (!accountRepository.twoFactorEnabled(username)) {
            throw new TwoFactorStateException("Two-factor authentication is off.");
        }
        requireCode(username, () -> acceptCode(username, code));
    }

    /**
     * Checks a code that was entered in the settings, with the login throttling.
     *
     * @throws LoginThrottledException  if the attempt has to wait; the code is not checked then
     * @throws FieldValidationException if the code is wrong
     */
    private void requireCode(String username, Supplier<Boolean> codeCheck) {
        loginThrottle.oneAtATime(username, () -> {
            Duration remainingWait = loginThrottle.remainingWait(username);
            if (remainingWait.isPositive()) {
                throw new LoginThrottledException(remainingWait);
            }
            if (!codeCheck.get()) {
                log.warn("Wrong code for two-factor authentication in the settings");
                Duration startedWait = loginThrottle.recordFailure(username);
                if (startedWait.isPositive()) {
                    long seconds = startedWait.toSeconds();
                    log.warn("Too many failed logins in a row; the next login has to wait {} {}", seconds,
                            seconds == 1 ? "second" : "seconds");
                }
                throw new FieldValidationException("code", WRONG_CODE);
            }
            loginThrottle.recordSuccess(username);
            return null;
        });
    }

    private void requirePassword(String username, String currentPassword) {
        if (!passwordVerifier.matches(username, currentPassword)) {
            throw new FieldValidationException("currentPassword", "The current password is wrong.");
        }
    }

    /** Compares in constant time, so the answer time says nothing about the right code. */
    private static boolean sameText(String expected, String entered) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                entered.getBytes(StandardCharsets.UTF_8));
    }

    /** The address an authenticator app reads from the QR code. */
    private static String otpauthUri(String username, String secret) {
        String label = URLEncoder.encode(ISSUER + ":" + username, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
    }
}
