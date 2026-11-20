package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.platform.WardroomProperties;
import io.github.stekhub.wardroom.server.internal.CredentialKeyLoader.LoadedKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads the key when the application starts, after the database is ready (spec 002). */
@Slf4j
@Configuration(proxyBeanMethods = false)
class CredentialEncryptionConfiguration {

    @Bean
    CredentialCipher credentialCipher(WardroomProperties properties, ServerRepository serverRepository) {
        LoadedKey loadedKey = CredentialKeyLoader.load(properties.dataDir(),
                properties.externalEncryptionKeyFile().orElse(null));
        if (loadedKey.generated() && serverRepository.markStoredCredentialsInvalid() > 0) {
            log.warn("The key file was missing and a new key was created. The stored NNTP password cannot be read "
                    + "with it; enter the password again in the server settings.");
        }
        return new CredentialCipher(loadedKey.key());
    }
}
