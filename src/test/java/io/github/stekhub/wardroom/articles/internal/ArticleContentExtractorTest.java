package io.github.stekhub.wardroom.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.ExtractedContent;
import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.OmittedPart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Body extraction and decoding (spec 007; ADR 0014). */
class ArticleContentExtractorTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    // Plain articles

    @Test
    void aSinglePartArticleGivesItsBodyAndItsHeaderBlock() {
        ExtractedContent content = extract("Subject: Hello", "From: Ada <ada@example.org>", "", "First line", "",
                "Second line");

        assertThat(content.body()).isEqualTo("First line\n\nSecond line");
        assertThat(content.rawHeaders()).isEqualTo("Subject: Hello\r\nFrom: Ada <ada@example.org>");
        assertThat(content.omittedParts()).isEmpty();
        assertThat(content.flowed()).isFalse();
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void theHeadersOfTheArticleAreReadForAStub() {
        ExtractedContent content = extract("Subject: =?UTF-8?Q?Gr=C3=BC=C3=9Fe?=", "From: Ada <ada@example.org>",
                "Date: Sat, 03 Jan 1970 00:00:00 +0000", "References: <a@example.org>", "\t<b@example.org>",
                "In-Reply-To: <b@example.org>", "Lines: 1", "", "Text");

        assertThat(content.headers().subject()).isEqualTo("Grüße");
        assertThat(content.headers().from()).isEqualTo("Ada <ada@example.org>");
        assertThat(content.headers().date()).isEqualTo(2 * 86_400_000L);
        assertThat(content.headers().references()).isEqualTo("<a@example.org> <b@example.org>");
        assertThat(content.headers().inReplyTo()).isEqualTo("<b@example.org>");
        assertThat(content.headers().lines()).isEqualTo(1);
    }

    @Test
    void anArticleWithoutTheseHeadersHasNoValuesForThem() {
        ExtractedContent content = extract("Path: news.example.org", "Date: not a date", "", "Text");

        assertThat(content.headers().subject()).isNull();
        assertThat(content.headers().date()).isNull();
        assertThat(content.headers().references()).isNull();
        assertThat(content.headers().inReplyTo()).isNull();
    }

    @Test
    void anArticleWithoutABodyHasEmptyText() {
        assertThat(extract("Subject: Hello").body()).isEmpty();
        assertThat(extract("Subject: Hello", "").body()).isEmpty();
    }

    @Test
    void flowedTextIsStoredAsReceivedAndMarked() {
        ExtractedContent content = extract("Content-Type: text/plain; charset=UTF-8; format=flowed; delsp=yes", "",
                "A long line that ", "goes on.");

        assertThat(content.body()).isEqualTo("A long line that \ngoes on.");
        assertThat(content.flowed()).isTrue();
        assertThat(content.delSp()).isTrue();
    }

    // Character sets

    @Test
    void aDeclaredCharacterSetIsUsed() {
        ExtractedContent content = extract(bytes(StandardCharsets.ISO_8859_1,
                "Content-Type: text/plain; charset=ISO-8859-15", "", "Preis: 5 ¤"));

        // 0xA4 is the euro sign in ISO-8859-15.
        assertThat(content.body()).isEqualTo("Preis: 5 €");
    }

    @Test
    void quotedPrintableAndBase64AreDecoded() {
        assertThat(extract("Content-Type: text/plain; charset=UTF-8", "Content-Transfer-Encoding: quoted-printable",
                "", "Gr=C3=BC=C3=9Fe =", "an alle").body()).isEqualTo("Grüße an alle");
        assertThat(extract("Content-Type: text/plain; charset=UTF-8", "Content-Transfer-Encoding: base64", "",
                Base64.getEncoder().encodeToString("Grüße".getBytes(StandardCharsets.UTF_8))).body())
                .isEqualTo("Grüße");
    }

    @Test
    void textWithoutACharacterSetIsReadAsUtf8() {
        ExtractedContent content = extract(bytes(StandardCharsets.UTF_8, "Subject: Hello", "",
                "Grüße – 日本"));

        assertThat(content.body()).isEqualTo("Grüße – 日本");
    }

    @Test
    void textWithoutACharacterSetThatIsNotUtf8IsReadAsWindows1252() {
        ExtractedContent content = extract(bytes(WINDOWS_1252, "Subject: Hello", "",
                "Grüße – 5 €"));

        assertThat(content.body()).isEqualTo("Grüße – 5 €");
    }

    @Test
    void anUnknownCharacterSetAndUsAsciiAreTreatedLikeNone() {
        assertThat(extract(bytes(StandardCharsets.UTF_8, "Content-Type: text/plain; charset=x-no-such-charset", "",
                "Grüße")).body()).isEqualTo("Grüße");
        assertThat(extract(bytes(WINDOWS_1252, "Content-Type: text/plain; charset=us-ascii", "",
                "Grüße")).body()).isEqualTo("Grüße");
    }

    @Test
    void invalidByteSequencesAreReplacedNotRejected() {
        ByteArrayOutputStream article = new ByteArrayOutputStream();
        article.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n\r\nbroken ".getBytes(StandardCharsets.UTF_8));
        article.write(0xC3);
        article.write(0x28);
        article.writeBytes(" text\r\n".getBytes(StandardCharsets.UTF_8));

        assertThat(extract(article.toByteArray()).body()).isEqualTo("broken �( text");
    }

    @Test
    void headerBytesThatAreNotUtf8AreKeptReadable() {
        ExtractedContent content = extract(bytes(StandardCharsets.ISO_8859_1, "Subject: Grüße", "", "Text"));

        assertThat(content.rawHeaders()).isEqualTo("Subject: Grüße");
        assertThat(content.headers().subject()).isEqualTo("Grüße");
    }

    // Parts

    @Test
    void ofAMultipartArticleTheFirstPlainTextIsShownAndTheOtherPartsAreOnlyListed() {
        ExtractedContent content = extract("Content-Type: multipart/mixed; boundary=\"frontier\"", "",
                "This is a message with multiple parts in MIME format.", "--frontier",
                "Content-Type: text/plain; charset=UTF-8", "", "The text.", "--frontier",
                "Content-Type: text/plain", "", "A second text.", "--frontier",
                "Content-Type: image/jpeg; name=\"photo.jpg\"", "Content-Transfer-Encoding: base64",
                "Content-Disposition: attachment; filename=\"photo.jpg\"", "",
                Base64.getEncoder().encodeToString(new byte[300]), "--frontier--");

        assertThat(content.body()).isEqualTo("The text.");
        assertThat(content.omittedParts()).containsExactly(new OmittedPart(null, "text/plain", 14),
                new OmittedPart("photo.jpg", "image/jpeg", 300));
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void ofAnAlternativeArticleThePlainTextIsShownAndTheHtmlIsListed() {
        ExtractedContent content = extract("Content-Type: multipart/alternative; boundary=b", "", "--b",
                "Content-Type: text/html; charset=UTF-8", "", "<p>Hello <script>alert(1)</script></p>", "--b",
                "Content-Type: text/plain; charset=UTF-8", "", "Hello", "--b--");

        assertThat(content.body()).isEqualTo("Hello");
        assertThat(content.omittedParts()).extracting(OmittedPart::mediaType).containsExactly("text/html");
    }

    @Test
    void anArticleThatIsOnlyHtmlHasNoTextAndListsTheHtml() {
        ExtractedContent content = extract("Content-Type: text/html; charset=UTF-8", "",
                "<p>Hello <script>alert(1)</script></p>");

        assertThat(content.body()).isEmpty();
        assertThat(content.omittedParts()).containsExactly(new OmittedPart(null, "text/html", 40));
    }

    @Test
    void theNameOfAPartIsDecodedCleanedAndCut() {
        ExtractedContent content = extract("Content-Type: multipart/mixed; boundary=b", "", "--b",
                "Content-Type: application/pdf; name=\"=?UTF-8?Q?Gr=C3=BC=C3=9Fe.pdf?=\"", "", "x", "--b",
                "Content-Type: application/zip; name=\"" + "n".repeat(300) + "\"", "", "x", "--b--");

        assertThat(content.omittedParts().get(0).name()).isEqualTo("Grüße.pdf");
        assertThat(content.omittedParts().get(1).name()).hasSize(200);
    }

    @Test
    void binaryContentWrittenIntoTheTextIsTakenOutAndListed() {
        ExtractedContent content = extract("Subject: Picture", "", "Look at this:",
                "=ybegin line=128 size=4096 name=photo.jpg", ")=J*:-=n=@", "=yend size=4096", "Nice, is it not?",
                "begin 644 notes.txt", "M" + "A".repeat(60), "`", "end", "Bye");

        assertThat(content.body()).isEqualTo("Look at this:\nNice, is it not?\nBye");
        assertThat(content.omittedParts()).containsExactly(
                new OmittedPart("photo.jpg", "application/octet-stream", 4096),
                new OmittedPart("notes.txt", "application/octet-stream", 45));
    }

    @Test
    void aLineThatOnlyLooksLikeTheStartOfEncodedContentStaysText() {
        ExtractedContent content = extract("Subject: Words", "", "begin 644 times", "with something new");

        assertThat(content.body()).isEqualTo("begin 644 times\nwith something new");
        assertThat(content.omittedParts()).isEmpty();
    }

    // Broken articles and limits

    @Test
    void anArticleWhoseMimeStructureCannotBeReadIsTakenAsPlainText() {
        ExtractedContent content = extract(bytes(StandardCharsets.UTF_8,
                "Content-Type: multipart/mixed; boundary=\"missing", "", "No boundary ever comes.", "Grüße"));

        assertThat(content.body()).contains("No boundary ever comes.").contains("Grüße");
    }

    @Test
    void textLongerThanTheLimitIsCutOff() {
        String line = "ä".repeat(499);
        StringBuilder article = new StringBuilder("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
        // 1,000 bytes per line with its line ending: 1,200 lines are more than 1 MB.
        for (int number = 0; number < 1_200; number++) {
            article.append(line).append("\r\n");
        }

        ExtractedContent content = extract(article.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(content.truncated()).isTrue();
        assertThat(content.body().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ArticleContentExtractor.MAX_TEXT_BYTES)
                .isGreaterThan(ArticleContentExtractor.MAX_TEXT_BYTES - 1_000);
    }

    @Test
    void anArticleThatWasCutOffByTheServerLimitIsMarked() {
        byte[] article = bytes(StandardCharsets.UTF_8, "Subject: Hello", "", "The beginning");

        assertThat(ArticleContentExtractor.extract(article, false).truncated()).isTrue();
        assertThat(ArticleContentExtractor.extract(article, false).body()).isEqualTo("The beginning");
    }

    @Test
    void partsAfterTheLimitAreNotProcessed() {
        StringBuilder article = new StringBuilder("Content-Type: multipart/mixed; boundary=b\r\n\r\n");
        article.append("--b\r\nContent-Type: text/plain\r\n\r\nThe text.\r\n");
        for (int number = 0; number < 150; number++) {
            article.append("--b\r\nContent-Type: application/octet-stream\r\n\r\nx\r\n");
        }
        article.append("--b--\r\n");

        ExtractedContent content = extract(article.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(content.body()).isEqualTo("The text.");
        assertThat(content.omittedParts()).hasSize(ArticleContentExtractor.MAX_PARTS - 1);
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void partsNestedDeeperThanTheLimitAreNotProcessed() {
        int levels = 30;
        StringBuilder article = new StringBuilder();
        for (int level = 0; level < levels; level++) {
            article.append("Content-Type: multipart/mixed; boundary=b").append(level).append("\r\n\r\n");
            article.append("--b").append(level).append("\r\n");
        }
        article.append("Content-Type: text/plain\r\n\r\nDeep inside.\r\n");
        for (int level = levels - 1; level >= 0; level--) {
            article.append("--b").append(level).append("--\r\n");
        }

        ExtractedContent content = extract(article.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(content.body()).isEmpty();
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void tenLevelsOfNestingAreStillProcessed() {
        int levels = ArticleContentExtractor.MAX_NESTING_DEPTH;
        StringBuilder article = new StringBuilder();
        for (int level = 0; level < levels; level++) {
            article.append("Content-Type: multipart/mixed; boundary=b").append(level).append("\r\n\r\n");
            article.append("--b").append(level).append("\r\n");
        }
        article.append("Content-Type: text/plain\r\n\r\nInside.\r\n");
        for (int level = levels - 1; level >= 0; level--) {
            article.append("--b").append(level).append("--\r\n");
        }

        ExtractedContent content = extract(article.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(content.body()).isEqualTo("Inside.");
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void aNulCharacterIsRemovedFromTheText() {
        assertThat(extract("Subject: x", "", "a\u0000b").body()).isEqualTo("ab");
    }

    private static ExtractedContent extract(String... lines) {
        return extract(bytes(StandardCharsets.UTF_8, lines));
    }

    private static ExtractedContent extract(byte[] article) {
        return ArticleContentExtractor.extract(article, true);
    }

    private static byte[] bytes(Charset charset, String... lines) {
        StringBuilder article = new StringBuilder();
        for (String line : lines) {
            article.append(line).append("\r\n");
        }
        return article.toString().getBytes(charset);
    }
}
