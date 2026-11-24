package io.github.stekhub.wardroom.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts secrets for the database with the key file and decrypts them again (spec 002, spec 012, ADR 0025).
 *
 * <p>Every caller names a purpose, such as {@code nntp-password}. The purpose is part of the encryption as
 * associated data, so a stored value of one purpose cannot be decrypted as another.
 *
 * <p>The stored bytes are: one version byte, a random 12-byte nonce, then the ciphertext with its 16-byte tag.
 * Both versions are AES-256-GCM with the key file. Version {@code 1} has no purpose; it was written only for the
 * NNTP password, before purposes existed, and is still read for that purpose. New values are written in version
 * {@code 2}. The version byte allows other algorithms and a key rotation later.
 */
public final class SecretCipher {

    /** The only purpose that was ever written in version 1. */
    public static final String PURPOSE_OF_VERSION_1 = "nntp-password";

    static final byte VERSION_WITHOUT_PURPOSE = 1;
    static final byte VERSION_WITH_PURPOSE = 2;
    static final int NONCE_LENGTH_BYTES = 12;
    static final int TAG_LENGTH_BYTES = 16;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final boolean keyWasGenerated;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(SecretKey key, boolean keyWasGenerated) {
        this.key = key;
        this.keyWasGenerated = keyWasGenerated;
    }

    /** @return whether the key was created by this start; secrets stored earlier cannot be read with it */
    public boolean keyWasGenerated() {
        return keyWasGenerated;
    }

    public byte[] encrypt(String purpose, String secret) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertextAndTag = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] stored = new byte[1 + NONCE_LENGTH_BYTES + ciphertextAndTag.length];
            stored[0] = VERSION_WITH_PURPOSE;
            System.arraycopy(nonce, 0, stored, 1, NONCE_LENGTH_BYTES);
            System.arraycopy(ciphertextAndTag, 0, stored, 1 + NONCE_LENGTH_BYTES, ciphertextAndTag.length);
            return stored;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The Java runtime cannot encrypt with AES-256-GCM", e);
        }
    }

    /**
     * @throws SecretDecryptionException when the bytes were not written by {@link #encrypt(String, String)} with
     *                                   this key and this purpose, or were changed since
     */
    public String decrypt(String purpose, byte[] stored) throws SecretDecryptionException {
        if (stored == null || stored.length < 1 + NONCE_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw new SecretDecryptionException("The stored secret is incomplete");
        }
        byte version = stored[0];
        if (version != VERSION_WITH_PURPOSE
                && !(version == VERSION_WITHOUT_PURPOSE && PURPOSE_OF_VERSION_1.equals(purpose))) {
            throw new SecretDecryptionException("The stored secret has the unknown format version " + version);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BYTES * 8, Arrays.copyOfRange(stored, 1, 1 + NONCE_LENGTH_BYTES)));
            if (version == VERSION_WITH_PURPOSE) {
                cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            }
            byte[] secret = cipher.doFinal(stored, 1 + NONCE_LENGTH_BYTES, stored.length - 1 - NONCE_LENGTH_BYTES);
            return new String(secret, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // The cause is left out on purpose: it adds nothing, and nothing about the key belongs in a log.
            throw new SecretDecryptionException("The stored secret does not match the key or the purpose");
        }
    }
}
