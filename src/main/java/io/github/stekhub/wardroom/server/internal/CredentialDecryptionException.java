package io.github.stekhub.wardroom.server.internal;

/** A stored password cannot be decrypted, for example because the key was replaced. The message has no secrets. */
class CredentialDecryptionException extends Exception {

    CredentialDecryptionException(String message) {
        super(message);
    }
}
