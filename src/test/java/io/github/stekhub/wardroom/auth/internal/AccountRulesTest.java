package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.stekhub.wardroom.platform.FieldValidationException;
import org.junit.jupiter.api.Test;

/** The validation boundaries of spec 001. */
class AccountRulesTest {

    @Test
    void usernameMayHaveOneToSixtyFourCharacters() {
        assertThat(AccountRules.validUsername("username", "a")).isEqualTo("a");
        assertThat(AccountRules.validUsername("username", "a".repeat(64))).hasSize(64);

        assertInvalidUsername("");
        assertInvalidUsername("a".repeat(65));
    }

    @Test
    void usernameLengthIsCountedAfterRemovingSurroundingWhitespace() {
        assertThat(AccountRules.validUsername("username", "  " + "a".repeat(64) + "\t")).isEqualTo("a".repeat(64));
        assertThat(AccountRules.validUsername("username", " two words ")).isEqualTo("two words");

        assertInvalidUsername("   ");
    }

    @Test
    void usernameKeepsItsCase() {
        assertThat(AccountRules.validUsername("username", "Owner")).isEqualTo("Owner");
    }

    @Test
    void passwordMayHaveTwelveToOneHundredTwentyEightCharacters() {
        assertThatCode(() -> AccountRules.requireValidPassword("password", "p".repeat(12))).doesNotThrowAnyException();
        assertThatCode(() -> AccountRules.requireValidPassword("password", "p".repeat(128)))
                .doesNotThrowAnyException();

        assertInvalidPassword("p".repeat(11));
        assertInvalidPassword("p".repeat(129));
        assertInvalidPassword("");
    }

    @Test
    void passwordIsNotTrimmedAndHasNoCompositionRules() {
        assertThatCode(() -> AccountRules.requireValidPassword("password", " ".repeat(12)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AccountRules.requireValidPassword("password", "aaaaaaaaaaaa"))
                .doesNotThrowAnyException();
    }

    @Test
    void lengthsAreCountedInCharactersNotInCodeUnits() {
        String emoji = "😀";

        assertThat(emoji).hasSize(2);
        assertThat(AccountRules.validUsername("username", emoji.repeat(64))).isEqualTo(emoji.repeat(64));
        assertThatCode(() -> AccountRules.requireValidPassword("password", emoji.repeat(128)))
                .doesNotThrowAnyException();
        assertInvalidPassword(emoji.repeat(11));
    }

    @Test
    void failureNamesTheFieldAndTheRuleButNotTheValue() {
        String secret = "tooshort";

        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(() -> AccountRules.requireValidPassword("newPassword", secret))
                .satisfies(exception -> {
                    assertThat(exception.field()).isEqualTo("newPassword");
                    assertThat(exception.getMessage()).isEqualTo("The password must have 12 to 128 characters.")
                            .doesNotContain(secret);
                });
    }

    private static void assertInvalidUsername(String username) {
        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(() -> AccountRules.validUsername("username", username))
                .withMessage("The username must have 1 to 64 characters.");
    }

    private static void assertInvalidPassword(String password) {
        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(() -> AccountRules.requireValidPassword("password", password));
    }
}
