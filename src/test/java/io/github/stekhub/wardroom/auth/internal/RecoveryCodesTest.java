package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void createsTenDifferentCodesOfTenCharactersWithoutCharactersThatAreEasyToConfuse() {
        List<String> codes = RecoveryCodes.generate(new SecureRandom());

        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(code).matches("[2-9A-HJ-NP-Z]{10}"));
    }

    @Test
    void anEnteredCodeMayHaveSpacesHyphensAndSmallLetters() {
        assertThat(RecoveryCodes.normalized(" abcde-fgh23 \n")).isEqualTo("ABCDEFGH23");
        assertThat(RecoveryCodes.normalized("ABCDEFGH23")).isEqualTo("ABCDEFGH23");
    }

    @Test
    void hashIsSha256AsHexadecimalDigits() {
        assertThat(RecoveryCodes.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** RFC 4648, section 10. */
    @Test
    void base32EncodesAndDecodesTheValuesOfTheRfc() {
        assertThat(Base32.encode(ascii(""))).isEmpty();
        assertThat(Base32.encode(ascii("f"))).isEqualTo("MY");
        assertThat(Base32.encode(ascii("fo"))).isEqualTo("MZXQ");
        assertThat(Base32.encode(ascii("foo"))).isEqualTo("MZXW6");
        assertThat(Base32.encode(ascii("foob"))).isEqualTo("MZXW6YQ");
        assertThat(Base32.encode(ascii("fooba"))).isEqualTo("MZXW6YTB");
        assertThat(Base32.encode(ascii("foobar"))).isEqualTo("MZXW6YTBOI");

        assertThat(Base32.decode("MZXW6YTBOI")).isEqualTo(ascii("foobar"));
        assertThat(Base32.decode("MZXW6YQ")).isEqualTo(ascii("foob"));
        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        assertThat(Base32.encode(secret)).hasSize(32);
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
