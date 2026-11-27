package io.github.stekhub.wardroom.ignoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The normalization of subjects and of ignored words (spec 023, "Matching"). */
class SubjectWordsTest {

    @Test
    void theExampleOfTheSpec() {
        assertThat(SubjectWords.of("Re: Java 25 — released!")).isEqualTo(" re java 25 released ");
    }

    @Test
    void caseDoesNotMatterAlsoForLettersOutsideAscii() {
        assertThat(SubjectWords.of("ÄPFEL und Birnen")).isEqualTo(" äpfel und birnen ");
        assertThat(SubjectWords.ofRule("Äpfel")).isEqualTo("äpfel");
    }

    @Test
    void punctuationAndRunsOfSpacesBecomeOneSpace() {
        assertThat(SubjectWords.of("  [java]   question??  ")).isEqualTo(" java question ");
        assertThat(SubjectWords.of("re-post_of\tC++")).isEqualTo(" re post of c ");
    }

    @Test
    void digitsAreKept() {
        assertThat(SubjectWords.of("Windows 11 24H2")).isEqualTo(" windows 11 24h2 ");
    }

    @Test
    void aSubjectWithoutLetterOrDigitGivesOneSpaceAndNoSubjectGivesNone() {
        assertThat(SubjectWords.of("?!")).isEqualTo(" ");
        assertThat(SubjectWords.of("")).isEqualTo(" ");
        assertThat(SubjectWords.of(null)).isNull();
    }

    @Test
    void theWordOfARuleIsNormalizedTheSameWayWithoutTheSpacesAround() {
        assertThat(SubjectWords.ofRule("  Re-Post ")).isEqualTo("re post");
        assertThat(SubjectWords.ofRule("C++")).isEqualTo("c");
        assertThat(SubjectWords.ofRule("%_\\")).isEmpty();
        assertThat(SubjectWords.ofRule(null)).isEmpty();
    }

    /** Whole words match, not parts of words, and a phrase matches its words in this order only. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "java | Java 25 released | true",
        "java | [java] question | true",
        "java | JavaScript tips | false",
        "java | Why I left Java | true",
        "for sale | FOR SALE: bicycle | true",
        "for sale | Sale for members | false",
        "for sale | for the sale | false",
    })
    void aRuleMatchesWholeWordsInOrder(String ruleText, String subject, boolean matches) {
        assertThat(SubjectWords.of(subject).contains(" " + SubjectWords.ofRule(ruleText) + " ")).isEqualTo(matches);
    }
}
