package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.platform.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * When the key file was missing and a new key was created, the stored NNTP passwords cannot be read any more
 * (spec 002). They are marked when the application starts, so the owner is asked to enter them again.
 */
@Slf4j
@Component
class CredentialKeyStartup {

    CredentialKeyStartup(SecretCipher secretCipher, ServerRepository serverRepository) {
        if (secretCipher.keyWasGenerated() && serverRepository.markStoredCredentialsInvalid() > 0) {
            log.warn("The key file was missing and a new key was created. The stored NNTP password cannot be read "
                    + "with it; enter the password again in the server settings.");
        }
    }
}
