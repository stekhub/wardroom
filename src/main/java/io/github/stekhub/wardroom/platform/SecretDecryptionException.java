package io.github.stekhub.wardroom.platform;

/** A stored secret cannot be decrypted with the current key. The message has no secrets. */
public class SecretDecryptionException extends Exception {

    SecretDecryptionException(String message) {
        super(message);
    }
}
