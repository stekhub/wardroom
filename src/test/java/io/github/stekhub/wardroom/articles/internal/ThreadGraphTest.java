package io.github.stekhub.wardroom.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.articles.internal.ThreadGraph.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The threading function of spec 006, without a database. Message-IDs are written without a host here, such as
 * {@code <a>}.
 */
class ThreadGraphTest {

    /** An article as it arrives: with its article number, its headers, and no place yet. */
    private record Arrival(long number, String messageId, String references, String inReplyTo) {
    }

    /** Stores and threads articles one after the other, as the application does. */
    private static final class Threading {

        private final ThreadGraph threadGraph = new ThreadGraph();
        private final List<String> ignoredCycles = new ArrayList<>();
        private long nextArticleId = 1;

        Threading arrive(String messageId, String references) {
            return arrive(messageId, references, null);
        }

        Threading arrive(String messageId, String references, String inReplyTo) {
            if (threadGraph.find(messageId) == null) {
                threadGraph.addStored(messageId, nextArticleId++, false);
            }
            if (threadGraph.thread(messageId, ThreadGraph.ancestry(messageId, references, inReplyTo)).cycleIgnored()) {
                ignoredCycles.add(messageId);
            }
            for (Node stub : threadGraph.createdStubs()) {
                if (stub.articleId() == null) {
                    stub.storedAs(nextArticleId++);
                }
            }
            return this;
        }

        String parentOf(String messageId) {
            Node parent = threadGraph.find(messageId).parent();
            return parent == null ? null : parent.messageId();
        }

        String rootOf(String messageId) {
            return threadGraph.rootOf(threadGraph.find(messageId)).messageId();
        }

        boolean isStub(String messageId) {
            return threadGraph.find(messageId).isStub();
        }

        /** @return every article and stub with its parent and whether it is a stub, by Message-ID */
        Map<String, String> structure(List<String> messageIds) {
            Map<String, String> structure = new TreeMap<>();
            for (String messageId : messageIds) {
                if (threadGraph.find(messageId) != null) {
                    structure.put(messageId, parentOf(messageId) + " root " + rootOf(messageId)
                            + (isStub(messageId) ? " stub" : ""));
                }
            }
            return structure;
        }
    }

    /** A complete recomputation: the articles are threaded by article number, oldest first. */
    private static Threading recompute(List<Arrival> arrivals) {
        List<Arrival> oldestFirst = new ArrayList<>(arrivals);
        oldestFirst.sort(Comparator.comparingLong(Arrival::number));
        Threading threading = new Threading();
        // All articles are stored before the first one is threaded.
        for (Arrival arrival : oldestFirst) {
            threading.threadGraph.addStored(arrival.messageId(), threading.nextArticleId++, false);
        }
        for (Arrival arrival : oldestFirst) {
            threading.arrive(arrival.messageId(), arrival.references(), arrival.inReplyTo());
        }
        return threading;
    }

    // Parent and root

    @Test
    void theParentIsTheLastReferenceAndTheRootIsTheTopmostAncestor() {
        Threading threading = new Threading()
                .arrive("<a>", null)
                .arrive("<b>", "<a>")
                .arrive("<c>", "<a> <b>");

        assertThat(threading.parentOf("<a>")).isNull();
        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.parentOf("<c>")).isEqualTo("<b>");
        assertThat(threading.rootOf("<c>")).isEqualTo("<a>");
        assertThat(threading.rootOf("<a>")).isEqualTo("<a>");
        assertThat(threading.isStub("<a>")).isFalse();
    }

    @Test
    void everyMissingReferenceBecomesAStubAndTheChainStaysConnected() {
        Threading threading = new Threading().arrive("<d>", "<a> <b> <c>");

        assertThat(threading.parentOf("<d>")).isEqualTo("<c>");
        assertThat(threading.parentOf("<c>")).isEqualTo("<b>");
        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.parentOf("<a>")).isNull();
        assertThat(threading.isStub("<a>")).isTrue();
        assertThat(threading.isStub("<b>")).isTrue();
        assertThat(threading.isStub("<c>")).isTrue();
        assertThat(threading.isStub("<d>")).isFalse();
        assertThat(threading.rootOf("<d>")).isEqualTo("<a>");
    }

    @Test
    void aStubInTheMiddleConnectsToTheStoredArticleListedBeforeIt() {
        Threading threading = new Threading()
                .arrive("<a>", null)
                .arrive("<d>", "<a> <b> <c>");

        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.isStub("<a>")).isFalse();
        assertThat(threading.rootOf("<d>")).isEqualTo("<a>");
    }

    @Test
    void shortenedReferencesKeepTheChainOfTheArticlesThatAreStored() {
        // The client of <d> dropped the middle of the chain, as RFC 5536 allows.
        Threading threading = new Threading()
                .arrive("<a>", null)
                .arrive("<b>", "<a>")
                .arrive("<c>", "<a> <b>")
                .arrive("<d>", "<a> <c>");

        assertThat(threading.parentOf("<d>")).isEqualTo("<c>");
        assertThat(threading.parentOf("<c>")).isEqualTo("<b>");
        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.rootOf("<d>")).isEqualTo("<a>");
    }

    @Test
    void aNewArticleNeverChangesTheParentOfAnArticleOrStubThatIsThere() {
        Threading threading = new Threading()
                .arrive("<b>", "<a>")
                .arrive("<d>", "<x> <c>")
                // Lists <b> and <c> after other Message-IDs than their parents.
                .arrive("<e>", "<z> <b> <c>");

        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.parentOf("<c>")).isEqualTo("<x>");
        assertThat(threading.parentOf("<z>")).isNull();
        assertThat(threading.parentOf("<e>")).isEqualTo("<c>");
    }

    @Test
    void aStubThatIsFilledInTakesItsParentFromItsOwnReferences() {
        Threading threading = new Threading()
                .arrive("<c>", "<a> <b>")
                .arrive("<b>", "<x>");

        assertThat(threading.isStub("<b>")).isFalse();
        assertThat(threading.parentOf("<b>")).isEqualTo("<x>");
        assertThat(threading.isStub("<x>")).isTrue();
        assertThat(threading.parentOf("<c>")).isEqualTo("<b>");
        assertThat(threading.rootOf("<c>")).isEqualTo("<x>");
        // The stub that was the parent before stays, as a thread of its own.
        assertThat(threading.parentOf("<a>")).isNull();
    }

    @Test
    void aStubThatIsFilledInWithoutReferencesBecomesAThreadRoot() {
        Threading threading = new Threading()
                .arrive("<c>", "<a> <b>")
                .arrive("<b>", null);

        assertThat(threading.parentOf("<b>")).isNull();
        assertThat(threading.rootOf("<c>")).isEqualTo("<b>");
    }

    // Cycles and malformed headers

    @Test
    void aLinkThatWouldCloseACycleIsIgnoredAndTheArticleBecomesAThreadRoot() {
        Threading threading = new Threading()
                .arrive("<b>", "<a>")
                .arrive("<a>", "<b>");

        assertThat(threading.ignoredCycles).containsExactly("<a>");
        assertThat(threading.parentOf("<a>")).isNull();
        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.rootOf("<b>")).isEqualTo("<a>");
    }

    @Test
    void aCycleOverSeveralArticlesIsFoundAsWell() {
        Threading threading = new Threading()
                .arrive("<c>", "<a> <b>")
                .arrive("<b>", "<a>")
                .arrive("<a>", "<c>");

        assertThat(threading.ignoredCycles).containsExactly("<a>");
        assertThat(threading.parentOf("<a>")).isNull();
        assertThat(threading.rootOf("<c>")).isEqualTo("<a>");
    }

    @Test
    void duplicatesTheOwnMessageIdAndMalformedTokensDoNotDisturbTheChain() {
        Threading threading = new Threading()
                .arrive("<c>", "<a> junk <b> <a> <c> <b d> <e\u0001> <b>");

        assertThat(threading.parentOf("<c>")).isEqualTo("<b>");
        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.ignoredCycles).isEmpty();
        assertThat(threading.threadGraph.find("<b d>")).isNull();
        assertThat(threading.threadGraph.find("<e\u0001>")).isNull();
    }

    @Test
    void anArticleWhoseReferencesHaveNothingUsableIsAThreadRoot() {
        Threading threading = new Threading().arrive("<a>", "see my earlier post <a> <b c>");

        assertThat(threading.parentOf("<a>")).isNull();
        assertThat(threading.threadGraph.createdStubs()).isEmpty();
    }

    // In-Reply-To

    @Test
    void inReplyToIsUsedOnlyWithoutReferencesAndItsFirstMessageIdIsTheParent() {
        Threading threading = new Threading()
                .arrive("<a>", null)
                .arrive("<b>", null, "<a> <other>")
                .arrive("<c>", "<b>", "<a>")
                .arrive("<d>", "  ", "text <missing> text");

        assertThat(threading.parentOf("<b>")).isEqualTo("<a>");
        assertThat(threading.threadGraph.find("<other>")).isNull();
        assertThat(threading.parentOf("<c>")).isEqualTo("<b>");
        assertThat(threading.parentOf("<d>")).isEqualTo("<missing>");
        assertThat(threading.isStub("<missing>")).isTrue();
    }

    // Idempotence and fetch order

    @Test
    void threadingAnArticleAgainChangesNothing() {
        Threading threading = new Threading()
                .arrive("<a>", null)
                .arrive("<c>", "<a> <b>")
                .arrive("<d>", "<a> <b> <c>");
        List<String> messageIds = List.of("<a>", "<b>", "<c>", "<d>");
        Map<String, String> before = threading.structure(messageIds);

        threading.arrive("<d>", "<a> <b> <c>").arrive("<c>", "<a> <b>").arrive("<a>", null);

        assertThat(threading.structure(messageIds)).isEqualTo(before);
        assertThat(threading.ignoredCycles).isEmpty();
    }

    @Test
    void aCompleteRecomputationGivesTheSameResultInWhateverOrderTheArticlesWereFetched() {
        List<Arrival> arrivals = new ArrayList<>(List.of(
                new Arrival(1, "<a>", null, null),
                new Arrival(2, "<b>", "<a>", null),
                // Two articles that list different Message-IDs before the missing <m>.
                new Arrival(3, "<c>", "<a> <m>", null),
                new Arrival(4, "<d>", "<b> <m>", null),
                new Arrival(5, "<e>", "<a> <m> <d>", null),
                new Arrival(6, "<f>", null, "<e>"),
                new Arrival(7, "<g>", "<x> <y>", null),
                new Arrival(8, "<h>", "<y> <x>", null)));
        List<String> messageIds = List.of("<a>", "<b>", "<c>", "<d>", "<e>", "<f>", "<g>", "<h>", "<m>", "<x>",
                "<y>");
        Map<String, String> expected = recompute(arrivals).structure(messageIds);
        // The article with the lowest number decides the parent of a stub.
        assertThat(expected.get("<m>")).isEqualTo("<a> root <a> stub");
        assertThat(expected.get("<y>")).isEqualTo("<x> root <x> stub");

        Random random = new Random(11);
        for (int run = 0; run < 10; run++) {
            Collections.shuffle(arrivals, random);
            assertThat(recompute(arrivals).structure(messageIds)).isEqualTo(expected);
        }
    }

    @Test
    void articlesThatArriveOldestFirstGetTheSamePlacesAsInACompleteRecomputation() {
        List<Arrival> arrivals = List.of(
                new Arrival(1, "<a>", null, null),
                new Arrival(2, "<c>", "<a> <b>", null),
                new Arrival(3, "<d>", "<a> <b> <c>", null),
                new Arrival(4, "<b>", "<a>", null));
        List<String> messageIds = List.of("<a>", "<b>", "<c>", "<d>");
        Threading oneByOne = new Threading();
        for (Arrival arrival : arrivals) {
            oneByOne.arrive(arrival.messageId(), arrival.references());
        }

        assertThat(oneByOne.structure(messageIds)).isEqualTo(recompute(arrivals).structure(messageIds));
    }
}
