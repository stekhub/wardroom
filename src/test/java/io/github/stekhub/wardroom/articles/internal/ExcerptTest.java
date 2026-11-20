package io.github.stekhub.wardroom.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The card excerpt and reading single headers (spec 007). */
class ExcerptTest {

    @Test
    void theExcerptLeavesOutQuotedLinesAndTheSignature() {
        String body = "Ada wrote:\n> an old line\n>> an older line\nI agree.\n\nMore   words\there.\n-- \nAda\n";

        assertThat(Excerpt.of(body)).isEqualTo("Ada wrote: I agree. More words here.");
    }

    @Test
    void theExcerptIsCutAfterTwoHundredCharacters() {
        String body = "word ".repeat(100);

        assertThat(Excerpt.of(body)).hasSize(199).startsWith("word word").endsWith("word");
    }

    @Test
    void charactersOutsideTheBasicPlaneCountAsOne() {
        String body = "😀".repeat(300);

        assertThat(Excerpt.of(body).codePointCount(0, Excerpt.of(body).length())).isEqualTo(200);
    }

    @Test
    void anArticleWithoutCachedContentOrWithoutOwnWordsHasNoExcerpt() {
        assertThat(Excerpt.of(null)).isNull();
        assertThat(Excerpt.of("")).isNull();
        assertThat(Excerpt.of("> only a quote\n-- \nAda")).isNull();
    }

    @Test
    void aHeaderIsFoundWithoutRegardToCaseAndUnfolded() {
        String headerBlock = "Subject: Hello\r\nin-reply-to: <a@example.org>\r\n\t<b@example.org>\r\nX-Other: 1";

        assertThat(RawHeaders.value(headerBlock, "In-Reply-To")).isEqualTo("<a@example.org> <b@example.org>");
        assertThat(RawHeaders.value(headerBlock, "Subject")).isEqualTo("Hello");
        assertThat(RawHeaders.value(headerBlock, "X-Other")).isEqualTo("1");
    }

    @Test
    void ofAHeaderThatAppearsTwiceTheFirstCounts() {
        assertThat(RawHeaders.value("Subject: One\nSubject: Two", "Subject")).isEqualTo("One");
    }

    @Test
    void aMissingHeaderAndAMissingBlockGiveNoValue() {
        assertThat(RawHeaders.value("Subject: Hello", "In-Reply-To")).isNull();
        assertThat(RawHeaders.value("X-In-Reply-To: <a@example.org>", "In-Reply-To")).isNull();
        assertThat(RawHeaders.value(null, "In-Reply-To")).isNull();
    }
}
