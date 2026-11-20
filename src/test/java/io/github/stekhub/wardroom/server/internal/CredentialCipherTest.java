package io.github.stekhub.wardroom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final String PASSWORD = "s3cret-Passw0rd with ümlaut";

    private final CredentialCipher cipher = new CredentialCipher(key(1));

    @Test
    void decryptsWhatItEncrypted() throws Exception {
        assertThat(cipher.decrypt(cipher.encrypt(PASSWORD))).isEqualTo(PASSWORD);
    }

    @Test
    void writesTheVersionByteTheNonceAndCiphertextWithTag() {
        byte[] stored = cipher.encrypt(PASSWORD);

        int passwordLength = PASSWORD.getBytes(StandardCharsets.UTF_8).length;
        assertThat(stored[0]).isEqualTo((byte) 1);
        assertThat(stored).hasSize(1 + 12 + passwordLength + 16);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("s3cret");
    }

    @Test
    void usesADifferentNonceForEveryEncryption() {
        byte[] first = cipher.encrypt(PASSWORD);
        byte[] second = cipher.encrypt(PASSWORD);

        assertThat(Arrays.copyOfRange(first, 1, 13)).isNotEqualTo(Arrays.copyOfRange(second, 1, 13));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        byte[] stored = cipher.encrypt(PASSWORD);

        assertThatThrownBy(() -> new CredentialCipher(key(2)).decrypt(stored))
                .isInstanceOf(CredentialDecryptionException.class)
                .hasMessage("The stored password does not match the key");
    }

    @Test
    void changedBytesAreRejected() {
        byte[] stored = cipher.encrypt(PASSWORD);
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(stored)).isInstanceOf(CredentialDecryptionException.class);
    }

    @Test
    void unknownVersionAndIncompleteDataAreRejected() {
        byte[] unknownVersion = cipher.encrypt(PASSWORD);
        unknownVersion[0] = 2;

        assertThatThrownBy(() -> cipher.decrypt(unknownVersion))
                .isInstanceOf(CredentialDecryptionException.class)
                .hasMessageContaining("unknown format version 2");
        assertThatThrownBy(() -> cipher.decrypt(new byte[] {1, 2, 3}))
                .isInstanceOf(CredentialDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(null)).isInstanceOf(CredentialDecryptionException.class);
    }

    private static SecretKey key(int fill) {
        byte[] keyBytes = new byte[32];
        Arrays.fill(keyBytes, (byte) fill);
        return new SecretKeySpec(keyBytes, "AES");
    }
}
