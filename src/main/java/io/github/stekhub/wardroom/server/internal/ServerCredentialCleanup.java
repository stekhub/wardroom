package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.auth.AccountCreationCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A new account never inherits the NNTP login of a previous one (ADR 0017): username and password are deleted and
 * the authentication is set to "none". The connection settings of the server stay.
 */
@Component
@RequiredArgsConstructor
class ServerCredentialCleanup implements AccountCreationCleanup {

    private final ServerRepository serverRepository;

    @Override
    public void removeSensitiveData() {
        serverRepository.removeAllCredentials();
    }
}
