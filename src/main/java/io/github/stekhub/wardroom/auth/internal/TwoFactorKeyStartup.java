package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.platform.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * When the key file was missing and a new key was created, the secret of two-factor authentication cannot be read
 * any more (spec 012, ADR 0035). The log says so when the application starts, and how the owner gets back in.
 */
@Slf4j
@Component
class TwoFactorKeyStartup {

    TwoFactorKeyStartup(SecretCipher secretCipher, AccountRepository accountRepository) {
        if (secretCipher.keyWasGenerated() && accountRepository.twoFactorSecretStored()) {
            log.warn("The key file was missing and a new key was created. The secret of two-factor authentication "
                    + "cannot be read with it, so codes from the authenticator app are not accepted. Log in with a "
                    + "recovery code, or reset the password as the user guide describes.");
        }
    }
}
