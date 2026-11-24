package io.github.stekhub.wardroom.auth.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Checks a password against the stored hash of the account (spec 001). */
@Component
class PasswordVerifier {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    /** A hash no password matches. It is checked when the username is unknown, so that login always takes as long. */
    private final String unusedPasswordHash;

    PasswordVerifier(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.unusedPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Always computes one password hash, also for an unknown username or a password that is too long to be valid. */
    boolean matches(String username, String password) {
        Optional<String> storedPasswordHash = accountRepository.findPasswordHash(username);
        boolean passwordCanBeValid = AccountRules.hasValidPasswordLength(password);
        boolean matches = passwordEncoder.matches(passwordCanBeValid ? password : "",
                storedPasswordHash.orElse(unusedPasswordHash));
        return storedPasswordHash.isPresent() && passwordCanBeValid && matches;
    }
}
