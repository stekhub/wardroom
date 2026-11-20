package io.github.stekhub.wardroom.server.internal;

/** The key for the stored NNTP password cannot be used; the application must not start (spec 002). */
class CredentialKeyException extends RuntimeException {

    CredentialKeyException(String message) {
        super(message);
    }

    CredentialKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
