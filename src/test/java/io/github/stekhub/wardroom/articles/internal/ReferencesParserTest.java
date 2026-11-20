package io.github.stekhub.wardroom.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Parsing {@code References} as spec 006 describes it. */
class ReferencesParserTest {

    private static final String OWN = "<own@example.org>";

    @Test
    void theMessageIdsAreReadInTheOrderOfTheHeader() {
        assertThat(ReferencesParser.parse("<a@example.org> <b@example.org>\t<c@example.org>", OWN))
                .containsExactly("<a@example.org>", "<b@example.org>", "<c@example.org>");
        assertThat(ReferencesParser.parse("<a@example.org><b@example.org>", OWN))
                .containsExactly("<a@example.org>", "<b@example.org>");
    }

    @Test
    void anArticleWithoutTheHeaderHasNoReferences() {
        assertThat(ReferencesParser.parse(null, OWN)).isEmpty();
        assertThat(ReferencesParser.parse("", OWN)).isEmpty();
        assertThat(ReferencesParser.parse("   ", OWN)).isEmpty();
    }

    @Test
    void whatIsNotInAngleBracketsIsIgnored() {
        assertThat(ReferencesParser.parse("Re: your mail <a@example.org> (see above), b@example.org > <", OWN))
                .containsExactly("<a@example.org>");
        assertThat(ReferencesParser.parse("<unfinished <a@example.org>", OWN)).containsExactly("<a@example.org>");
        assertThat(ReferencesParser.parse("no message id here", OWN)).isEmpty();
    }

    @Test
    void duplicatesAndTheMessageIdOfTheArticleItselfAreDropped() {
        assertThat(ReferencesParser.parse("<a@example.org> <b@example.org> <a@example.org> " + OWN, OWN))
                .containsExactly("<a@example.org>", "<b@example.org>");
    }

    @Test
    void messageIdsWithWhitespaceOrControlCharactersAndEmptyOnesAreDropped() {
        assertThat(ReferencesParser.parse("<a b@example.org> <c@example.org> <d\u0007@example.org> <>"
                + " <e\r\n@example.org> <f\u0000@example.org>", OWN)).containsExactly("<c@example.org>");
    }

    @Test
    void messageIdsAreNotChangedSoTheyCompareExactly() {
        assertThat(ReferencesParser.parse("<A@Example.ORG> <a@example.org>", OWN))
                .containsExactly("<A@Example.ORG>", "<a@example.org>");
    }

    @Test
    void ofVeryManyMessageIdsTheLastHundredAreUsed() {
        String header = IntStream.rangeClosed(1, 250).mapToObj(number -> "<" + number + "@example.org>")
                .collect(Collectors.joining(" "));

        List<String> messageIds = ReferencesParser.parse(header, OWN);

        assertThat(messageIds).hasSize(ReferencesParser.MAX_MESSAGE_IDS);
        assertThat(messageIds.getFirst()).isEqualTo("<151@example.org>");
        assertThat(messageIds.getLast()).isEqualTo("<250@example.org>");
    }

    @Test
    void aVeryLongCraftedHeaderIsReadQuickly() {
        String header = "<".repeat(2_000_000) + ">".repeat(2_000_000) + " <a@example.org>";

        List<String> messageIds = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> ReferencesParser.parse(header, OWN));

        assertThat(messageIds).containsExactly("<a@example.org>");
    }

    @Test
    void aHeaderCountsAsReferencesWhenItHasSomethingInAngleBrackets() {
        assertThat(ReferencesParser.hasMessageIdToken("<a@example.org>")).isTrue();
        assertThat(ReferencesParser.hasMessageIdToken("text <a b> text")).isTrue();
        assertThat(ReferencesParser.hasMessageIdToken(null)).isFalse();
        assertThat(ReferencesParser.hasMessageIdToken("")).isFalse();
        assertThat(ReferencesParser.hasMessageIdToken("> text <")).isFalse();
        assertThat(ReferencesParser.hasMessageIdToken("a@example.org")).isFalse();
    }
}
