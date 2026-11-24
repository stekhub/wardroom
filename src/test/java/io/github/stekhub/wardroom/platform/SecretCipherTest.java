package io.github.stekhub.wardroom.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String NNTP_PASSWORD = "nntp-password";
    private static final String TOTP_SECRET = "totp-secret";
    private static final String PASSWORD = "s3cret-Passw0rd with ümlaut";

    private final SecretCipher cipher = new SecretCipher(key(1), false);

    @Test
    void decryptsWhatItEncrypted() throws Exception {
        assertThat(cipher.decrypt(NNTP_PASSWORD, cipher.encrypt(NNTP_PASSWORD, PASSWORD))).isEqualTo(PASSWORD);
        assertThat(cipher.decrypt(TOTP_SECRET, cipher.encrypt(TOTP_SECRET, PASSWORD))).isEqualTo(PASSWORD);
    }

    @Test
    void writesTheVersionByteTheNonceAndCiphertextWithTag() {
        byte[] stored = cipher.encrypt(NNTP_PASSWORD, PASSWORD);

        int passwordLength = PASSWORD.getBytes(StandardCharsets.UTF_8).length;
        assertThat(stored[0]).isEqualTo((byte) 2);
        assertThat(stored).hasSize(1 + 12 + passwordLength + 16);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("s3cret");
    }

    @Test
    void usesADifferentNonceForEveryEncryption() {
        byte[] first = cipher.encrypt(NNTP_PASSWORD, PASSWORD);
        byte[] second = cipher.encrypt(NNTP_PASSWORD, PASSWORD);

        assertThat(Arrays.copyOfRange(first, 1, 13)).isNotEqualTo(Arrays.copyOfRange(second, 1, 13));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aValueOfOnePurposeCannotBeDecryptedAsAnother() {
        byte[] stored = cipher.encrypt(NNTP_PASSWORD, PASSWORD);

        assertThatThrownBy(() -> cipher.decrypt(TOTP_SECRET, stored))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessage("The stored secret does not match the key or the purpose");
    }

    @Test
    void anotherKeyCannotDecrypt() {
        byte[] stored = cipher.encrypt(NNTP_PASSWORD, PASSWORD);

        assertThatThrownBy(() -> new SecretCipher(key(2), false).decrypt(NNTP_PASSWORD, stored))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessage("The stored secret does not match the key or the purpose");
    }

    @Test
    void changedBytesAreRejected() {
        byte[] stored = cipher.encrypt(NNTP_PASSWORD, PASSWORD);
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(NNTP_PASSWORD, stored)).isInstanceOf(SecretDecryptionException.class);
    }

    /** NNTP passwords that were stored before purposes existed stay readable (spec 012). */
    @Test
    void version1IsStillReadForTheNntpPasswordOnly() throws Exception {
        byte[] storedInVersion1 = encryptedInVersion1(key(1), PASSWORD);

        assertThat(cipher.decrypt(NNTP_PASSWORD, storedInVersion1)).isEqualTo(PASSWORD);
        assertThatThrownBy(() -> cipher.decrypt(TOTP_SECRET, storedInVersion1))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessageContaining("unknown format version 1");
    }

    @Test
    void unknownVersionAndIncompleteDataAreRejected() {
        byte[] unknownVersion = cipher.encrypt(NNTP_PASSWORD, PASSWORD);
        unknownVersion[0] = 3;

        assertThatThrownBy(() -> cipher.decrypt(NNTP_PASSWORD, unknownVersion))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessageContaining("unknown format version 3");
        assertThatThrownBy(() -> cipher.decrypt(NNTP_PASSWORD, new byte[] {1, 2, 3}))
                .isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(NNTP_PASSWORD, null)).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void tellsWhetherTheKeyWasGenerated() {
        assertThat(cipher.keyWasGenerated()).isFalse();
        assertThat(new SecretCipher(key(1), true).keyWasGenerated()).isTrue();
    }

    /** The format that was written before version 2: no associated data. */
    private static byte[] encryptedInVersion1(SecretKey key, String secret) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 7);
        Cipher aesGcm = Cipher.getInstance("AES/GCM/NoPadding");
        aesGcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] ciphertextAndTag = aesGcm.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] stored = new byte[1 + nonce.length + ciphertextAndTag.length];
        stored[0] = 1;
        System.arraycopy(nonce, 0, stored, 1, nonce.length);
        System.arraycopy(ciphertextAndTag, 0, stored, 1 + nonce.length, ciphertextAndTag.length);
        return stored;
    }

    private static SecretKey key(int fill) {
        byte[] keyBytes = new byte[32];
        Arrays.fill(keyBytes, (byte) fill);
        return new SecretKeySpec(keyBytes, "AES");
    }
}
