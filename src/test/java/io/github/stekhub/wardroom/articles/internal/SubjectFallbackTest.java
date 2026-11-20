package io.github.stekhub.wardroom.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.articles.internal.SubjectFallback.Candidate;
import io.github.stekhub.wardroom.articles.internal.SubjectFallback.Decision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The subject fallback and the subject normalization of spec 006. */
class SubjectFallbackTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    /** An article without references and without a parent, decided in this group. */
    private static Candidate article(long articleId, String subject, long date) {
        return new Candidate(articleId, "<" + articleId + "@example.org>", subject, date, null, true, true);
    }

    // Normalization

    @ParameterizedTest
    @ValueSource(strings = {"Re: Hello", "RE: Hello", "re:Hello", "Aw: Hello", "AW: Hello", "Sv: Hello",
            "Antw: Hello", "Re[2]: Hello", "Re^2: Hello", "Re: Re: Hello", "Re: AW: re[12]:  Hello",
            "  Re:   Hello  ", "Re:\tHello"})
    void replyPrefixesAreRemovedAlsoRepeatedAndWithACounter(String subject) {
        assertThat(SubjectNormalizer.hasReplyPrefix(subject)).isTrue();
        assertThat(SubjectNormalizer.normalize(subject)).isEqualTo("hello");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hello", "Regarding: Hello", "Re Hello", "Fwd: Hello", "Hello Re: Hello", "Re[x]: Hello",
            "Rex: Hello", ""})
    void otherSubjectsHaveNoReplyPrefix(String subject) {
        assertThat(SubjectNormalizer.hasReplyPrefix(subject)).isFalse();
    }

    @Test
    void whitespaceIsCollapsedAndCaseIsIgnored() {
        assertThat(SubjectNormalizer.normalize("  Records   vs.\tLombok ")).isEqualTo("records vs. lombok");
        assertThat(SubjectNormalizer.normalize("Re: RECORDS vs. Lombok")).isEqualTo("records vs. lombok");
        assertThat(SubjectNormalizer.normalize(null)).isEmpty();
        assertThat(SubjectNormalizer.hasReplyPrefix(null)).isFalse();
    }

    @Test
    void aCraftedSubjectIsNormalizedQuickly() {
        String subject = "Re: ".repeat(200_000) + "re[" + "9".repeat(100_000) + " Hello";

        assertThat(SubjectNormalizer.normalize(subject)).endsWith("hello");
    }

    // The fallback

    @Test
    void aReplyWithoutReferencesAttachesToTheThreadRootWithTheSameSubject() {
        List<Decision> decisions = SubjectFallback.resolve(List.of(
                article(1, "Records vs. Lombok", 10 * DAY),
                article(2, "Re: records  vs. LOMBOK", 11 * DAY)));

        assertThat(decisions).containsExactly(new Decision(2, 1L));
    }

    @Test
    void theNewestEarlierThreadRootIsChosen() {
        List<Decision> decisions = SubjectFallback.resolve(List.of(
                article(1, "Hello", 10 * DAY),
                article(2, "Hello", 12 * DAY),
                article(3, "Hello", 20 * DAY),
                article(4, "Re: Hello", 15 * DAY)));

        assertThat(decisions).containsExactly(new Decision(4, 2L));
    }

    @Test
    void aThreadRootIsMatchedUpToThirtyDaysBeforeTheArticle() {
        assertThat(SubjectFallback.resolve(List.of(
                article(1, "Hello", 10 * DAY),
                article(2, "Re: Hello", 40 * DAY)))).containsExactly(new Decision(2, 1L));
        assertThat(SubjectFallback.resolve(List.of(
                article(1, "Hello", 10 * DAY),
                article(2, "Re: Hello", 40 * DAY + 1)))).containsExactly(new Decision(2, null));
    }

    @Test
    void anArticleWithoutAReplyPrefixIsNotAttached() {
        List<Decision> decisions = SubjectFallback.resolve(List.of(
                article(1, "Hello", 10 * DAY),
                article(2, "Hello", 11 * DAY)));

        assertThat(decisions).isEmpty();
    }

    @Test
    void anArticleWithReferencesIsLeftAloneAndIsNoThreadRootWhenItHasAParent() {
        Candidate withReferences = new Candidate(1, "<1@example.org>", "Re: Hello", 10 * DAY, 99L, false, true);
        Candidate rootByCycle = new Candidate(2, "<2@example.org>", "Hello", 11 * DAY, null, false, true);

        List<Decision> decisions = SubjectFallback.resolve(List.of(withReferences, rootByCycle,
                article(3, "Re: Hello", 12 * DAY)));

        assertThat(decisions).containsExactly(new Decision(3, 2L));
    }

    @Test
    void withoutTheOriginalTheSecondReplyAttachesToTheFirst() {
        List<Decision> decisions = SubjectFallback.resolve(List.of(
                article(1, "Re: Hello", 10 * DAY),
                article(2, "Re: Hello", 11 * DAY)));

        assertThat(decisions).containsExactly(new Decision(1, null), new Decision(2, 1L));
    }

    @Test
    void withTheOriginalAllRepliesAttachToItAndNotToEachOther() {
        List<Decision> decisions = SubjectFallback.resolve(List.of(
                article(1, "Hello", 9 * DAY),
                article(2, "Re: Hello", 10 * DAY),
                article(3, "Re: Hello", 11 * DAY)));

        assertThat(decisions).containsExactly(new Decision(2, 1L), new Decision(3, 1L));
    }

    @Test
    void aSubjectThatIsOnlyAPrefixMatchesNothing() {
        List<Decision> decisions = SubjectFallback.resolve(List.of(
                article(1, "Re:", 10 * DAY),
                article(2, "Re:", 11 * DAY)));

        assertThat(decisions).containsExactly(new Decision(1, null), new Decision(2, null));
    }

    @Test
    void anArticleThatAnotherGroupDecidesKeepsItsParent() {
        Candidate attachedElsewhere = new Candidate(2, "<2@example.org>", "Re: Hello", 11 * DAY, 77L, true, false);
        Candidate rootElsewhere = new Candidate(3, "<3@example.org>", "Re: Hello", 12 * DAY, null, true, false);

        List<Decision> decisions = SubjectFallback.resolve(List.of(article(1, "Hello", 10 * DAY), attachedElsewhere,
                rootElsewhere, article(4, "Re: Hello", 13 * DAY)));

        // The article that is a thread root elsewhere is the newest earlier thread root here.
        assertThat(decisions).containsExactly(new Decision(4, 3L));
    }

    @Test
    void theResultIsTheSameOnASecondRunAndInAnyOrder() {
        List<Candidate> candidates = new ArrayList<>();
        Random random = new Random(7);
        String[] subjects = {"Hello", "Re: Hello", "Aw: Re: hello", "Other", "Re: Other", "Re[3]: other"};
        for (int articleId = 1; articleId <= 300; articleId++) {
            candidates.add(article(articleId, subjects[random.nextInt(subjects.length)],
                    random.nextInt(120) * DAY + random.nextInt(1000)));
        }
        List<Decision> expected = SubjectFallback.resolve(candidates);

        for (int run = 0; run < 5; run++) {
            Collections.shuffle(candidates, random);
            assertThat(SubjectFallback.resolve(candidates)).isEqualTo(expected);
        }

        // Run again on the result: every article has the parent that was decided for it.
        List<Candidate> decided = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Long parentId = expected.stream().filter(decision -> decision.articleId() == candidate.articleId())
                    .findFirst().map(Decision::parentId).orElse(null);
            decided.add(new Candidate(candidate.articleId(), candidate.messageId(), candidate.subject(),
                    candidate.date(), parentId, true, true));
        }
        assertThat(SubjectFallback.resolve(decided)).isEqualTo(expected);
    }
}
