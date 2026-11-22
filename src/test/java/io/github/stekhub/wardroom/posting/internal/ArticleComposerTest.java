package io.github.stekhub.wardroom.posting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.nntp.NntpHeaderText;
import io.github.stekhub.wardroom.posting.internal.ArticleComposer.Composed;
import io.github.stekhub.wardroom.posting.internal.ArticleComposer.Parts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArticleComposerTest {

    private static final Instant SENT = Instant.parse("2026-11-22T19:05:09Z");

    @Test
    void anArticleHasTheHeadersOfTheSpecificationAndAFlowedBody() {
        Composed composed = ArticleComposer.compose(new Parts("Jane Doe", "jane@example.org",
                List.of("example.talk", "example.other"), "Records vs. Lombok", "<abc@example.org>", SENT,
                "<a@example.org> <b@example.org>", "Hello\n.dot\n-- \nJane", "1.2.3"));

        assertThat(composed.text()).isEqualTo("""
                From: Jane Doe <jane@example.org>
                Newsgroups: example.talk,example.other
                Subject: Records vs. Lombok
                Message-ID: <abc@example.org>
                Date: Sun, 22 Nov 2026 19:05:09 +0000
                References: <a@example.org> <b@example.org>
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8; format=flowed
                Content-Transfer-Encoding: 8bit
                User-Agent: Wardroom/1.2.3

                Hello
                .dot
                --\s
                Jane
                """.replace("\n", "\r\n"));
        assertThat(composed.headers() + "\r\n" + composed.body()).isEqualTo(composed.text());
        assertThat(composed.from()).isEqualTo("Jane Doe <jane@example.org>");
        assertThat(composed.subject()).isEqualTo("Records vs. Lombok");
        assertThat(composed.lines()).isEqualTo(4);
        assertThat(composed.bytes()).isEqualTo(composed.text().length());
    }

    @Test
    void aNewDiscussionHasNoReferences() {
        Composed composed = ArticleComposer.compose(parts("Jane", "Hello", null));

        assertThat(composed.headers()).doesNotContain("References");
    }

    @Test
    void aNameAndASubjectWithOtherThanAsciiAreEncodedAndReadBackAsTheyWere() {
        String name = "Jürgen Müller";
        String subject = "Grüße aus Köln 🙂 and a few more words so that the encoded subject does not fit one line";

        Composed composed = ArticleComposer.compose(parts(name, subject, null));

        assertThat(composed.headers()).isASCII();
        assertThat(composed.headers()).contains("From: =?").contains("Subject: =?");
        assertThat(composed.from()).isEqualTo(name + " <jane@example.org>");
        assertThat(composed.subject()).isEqualTo(subject);
        assertThat(composed.headers().lines()).allSatisfy(line -> assertThat(line.length()).isLessThan(100));
        // The headers as another reader finds them: folded lines joined, then decoded.
        assertThat(NntpHeaderText.decode(HeaderBlock.value(composed.headers(), "Subject"))).isEqualTo(subject);
        assertThat(NntpHeaderText.decode(HeaderBlock.value(composed.headers(), "From")))
                .isEqualTo(name + " <jane@example.org>");
    }

    @Test
    void aNameWithSpecialCharactersIsQuoted() {
        Composed composed = ArticleComposer.compose(parts("Doe, Jane (home)", "Hello", null));

        assertThat(composed.headers()).contains("From: \"Doe, Jane (home)\" <jane@example.org>\r\n");
    }

    @Test
    void lineBreaksInTheNameAndTheSubjectDoNotAddHeaders() {
        Composed composed = ArticleComposer.compose(parts("Jane\r\nBcc: someone@example.org",
                "Hello\nX-Injected: yes\r\n\r\nbody", null));

        assertThat(composed.headers().lines().filter(line -> !line.startsWith(" ")).map(
                line -> line.substring(0, line.indexOf(':')))).containsExactly("From", "Newsgroups", "Subject",
                "Message-ID", "Date", "MIME-Version", "Content-Type", "Content-Transfer-Encoding", "User-Agent");
        assertThat(composed.subject()).isEqualTo("HelloX-Injected: yesbody");
        assertThat(composed.body()).isEqualTo("Text\r\n");
    }

    @Test
    void theReferencesOfAReplyAreThoseOfTheParentAndTheParent() {
        assertThat(ArticleComposer.references("<a@example.org>\t<b@example.org> junk", "<c@example.org>"))
                .isEqualTo("<a@example.org> <b@example.org> <c@example.org>");
        assertThat(ArticleComposer.references(null, "<c@example.org>")).isEqualTo("<c@example.org>");
    }

    @Test
    void referencesThatAreTooLongKeepTheFirstAndTheLastThree() {
        List<String> messageIds = new ArrayList<>();
        for (int number = 1; number <= 60; number++) {
            messageIds.add("<message-number-" + number + "@news.example.org>");
        }

        String references = ArticleComposer.references(String.join(" ", messageIds), "<parent@example.org>");

        assertThat(("References: " + references).length()).isLessThanOrEqualTo(998);
        List<String> kept = List.of(references.split(" "));
        assertThat(kept).first().isEqualTo(messageIds.getFirst());
        assertThat(kept.subList(kept.size() - 3, kept.size())).containsExactly(messageIds.get(58), messageIds.get(59),
                "<parent@example.org>");
        // Only as many as needed are dropped, from the middle.
        assertThat(kept.size()).isGreaterThan(20);
        assertThat(kept).doesNotContain(messageIds.get(1));
    }

    @Test
    void aReplyGoesToTheGroupsOfFollowupToOtherwiseToThoseOfNewsgroups() {
        assertThat(ArticleComposer.targetGroups("example.followup", "example.talk,example.other"))
                .containsExactly("example.followup");
        assertThat(ArticleComposer.targetGroups(null, " example.talk , example.other,example.talk"))
                .containsExactly("example.talk", "example.other");
        assertThat(ArticleComposer.targetGroups("", "example.talk")).containsExactly("example.talk");
    }

    @Test
    void entriesThatAreNoGroupNamesAreDropped() {
        assertThat(ArticleComposer.targetGroups(null,
                "example.talk,bad name,example..double,.leading,example.ok+plus,x\r\nBcc: y,,"))
                .containsExactly("example.talk", "example.ok+plus");
        assertThat(ArticleComposer.targetGroups("not valid!", null)).isEmpty();
    }

    @Test
    void followupToPosterAsksForRepliesByEmail() {
        assertThat(ArticleComposer.repliesByEmailOnly(" Poster ")).isTrue();
        assertThat(ArticleComposer.repliesByEmailOnly("example.talk")).isFalse();
        assertThat(ArticleComposer.repliesByEmailOnly(null)).isFalse();
    }

    @Test
    void aMessageIdHasTwentyRandomCharactersAndTheDomainOfTheEmailAddress() {
        String first = ArticleComposer.newMessageId("jane@news.example.org");
        String second = ArticleComposer.newMessageId("jane@news.example.org");

        assertThat(first).matches("<[A-Za-z0-9]{20}@news\\.example\\.org>");
        assertThat(second).isNotEqualTo(first);
        assertThat(ArticleComposer.isOwnMessageId(first, "other@news.example.org")).isTrue();
    }

    @Test
    void aMessageIdOfAnotherFormOrDomainIsNotOneOfWardroom() {
        String email = "jane@example.org";

        assertThat(ArticleComposer.isOwnMessageId("<abcdefghijklmnopqrst@example.com>", email)).isFalse();
        assertThat(ArticleComposer.isOwnMessageId("<short@example.org>", email)).isFalse();
        assertThat(ArticleComposer.isOwnMessageId("<abcdefghij-lmnopqrst@example.org>", email)).isFalse();
        assertThat(ArticleComposer.isOwnMessageId("<abcdefghijklmnopqrst@example.org> <x@example.org>", email))
                .isFalse();
        assertThat(ArticleComposer.isOwnMessageId(null, email)).isFalse();
        assertThat(ArticleComposer.isOwnMessageId("<abcdefghijklmnopqrst@example.org>", email)).isTrue();
    }

    private static Parts parts(String name, String subject, String references) {
        return new Parts(name, "jane@example.org", List.of("example.talk"), subject, "<abc@example.org>", SENT,
                references, "Text", "1.2.3");
    }
}
