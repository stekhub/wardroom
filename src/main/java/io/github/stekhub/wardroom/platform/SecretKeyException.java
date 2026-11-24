package io.github.stekhub.wardroom.platform;

/** The key for stored secrets cannot be used; the application must not start (spec 002, ADR 0025). */
public class SecretKeyException extends RuntimeException {

    SecretKeyException(String message) {
        super(message);
    }

    SecretKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
