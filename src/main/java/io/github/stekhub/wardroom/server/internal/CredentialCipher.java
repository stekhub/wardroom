package io.github.stekhub.wardroom.server.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the NNTP password for the database and decrypts it again (spec 002).
 *
 * <p>The stored bytes are: one version byte, a random 12-byte nonce, then the ciphertext with its 16-byte tag.
 * Version {@code 1} is AES-256-GCM with the key file. The version byte allows other algorithms and a key rotation
 * later.
 */
final class CredentialCipher {

    static final byte VERSION_AES_256_GCM = 1;
    static final int NONCE_LENGTH_BYTES = 12;
    static final int TAG_LENGTH_BYTES = 16;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    CredentialCipher(SecretKey key) {
        this.key = key;
    }

    byte[] encrypt(String password) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce));
            byte[] ciphertextAndTag = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] stored = new byte[1 + NONCE_LENGTH_BYTES + ciphertextAndTag.length];
            stored[0] = VERSION_AES_256_GCM;
            System.arraycopy(nonce, 0, stored, 1, NONCE_LENGTH_BYTES);
            System.arraycopy(ciphertextAndTag, 0, stored, 1 + NONCE_LENGTH_BYTES, ciphertextAndTag.length);
            return stored;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The Java runtime cannot encrypt with AES-256-GCM", e);
        }
    }

    /**
     * @throws CredentialDecryptionException when the bytes were not written by {@link #encrypt(String)} with this
     *                                       key, or were changed since
     */
    String decrypt(byte[] stored) throws CredentialDecryptionException {
        if (stored == null || stored.length < 1 + NONCE_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw new CredentialDecryptionException("The stored password is incomplete");
        }
        if (stored[0] != VERSION_AES_256_GCM) {
            throw new CredentialDecryptionException("The stored password has the unknown format version "
                    + stored[0]);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BYTES * 8, Arrays.copyOfRange(stored, 1, 1 + NONCE_LENGTH_BYTES)));
            byte[] password = cipher.doFinal(stored, 1 + NONCE_LENGTH_BYTES, stored.length - 1 - NONCE_LENGTH_BYTES);
            return new String(password, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // The cause is left out on purpose: it adds nothing, and nothing about the key belongs in a log.
            throw new CredentialDecryptionException("The stored password does not match the key");
        }
    }
}
