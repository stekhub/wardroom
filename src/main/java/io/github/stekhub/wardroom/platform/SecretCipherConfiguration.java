package io.github.stekhub.wardroom.platform;

import io.github.stekhub.wardroom.platform.SecretKeyLoader.LoadedKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads the key when the application starts (spec 002, ADR 0025). */
@Configuration(proxyBeanMethods = false)
class SecretCipherConfiguration {

    @Bean
    SecretCipher secretCipher(WardroomProperties properties) {
        LoadedKey loadedKey = SecretKeyLoader.load(properties.dataDir(),
                properties.externalEncryptionKeyFile().orElse(null));
        return new SecretCipher(loadedKey.key(), loadedKey.generated());
    }
}
