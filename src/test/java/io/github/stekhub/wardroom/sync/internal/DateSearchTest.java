package io.github.stekhub.wardroom.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.sync.internal.DateSearch.DatesUnreadableException;
import io.github.stekhub.wardroom.sync.internal.DateSearch.Start;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The date search of spec 005, against a group that exists only in memory. */
class DateSearchTest {

    private static final int DEPTH = 500;

    /** A group whose article with the number n was written at the time n × 1,000, unless a test says otherwise. */
    private static final class Group implements DateSearch.OverviewWindows {

        private final long lowestNumber;
        private final long highestNumber;
        private final Set<Long> missingNumbers = new HashSet<>();
        private final Map<Long, Long> otherDates = new HashMap<>();
        private final Set<Long> undatedNumbers = new HashSet<>();
        private final List<String> probes = new ArrayList<>();

        Group(long lowestNumber, long highestNumber) {
            this.lowestNumber = lowestNumber;
            this.highestNumber = highestNumber;
        }

        void withoutArticles(long firstNumber, long lastNumber) {
            for (long number = firstNumber; number <= lastNumber; number++) {
                missingNumbers.add(number);
            }
        }

        @Override
        public List<NntpOverview> fetch(long firstNumber, long lastNumber) {
            probes.add(firstNumber + "-" + lastNumber);
            List<NntpOverview> overviews = new ArrayList<>();
            for (long number = Math.max(firstNumber, lowestNumber); number <= Math.min(lastNumber, highestNumber);
                    number++) {
                if (missingNumbers.contains(number)) {
                    continue;
                }
                Long date = undatedNumbers.contains(number) ? null : otherDates.getOrDefault(number, number * 1_000);
                overviews.add(new NntpOverview(number, "Subject", "Ada <ada@example.org>", date,
                        "<" + number + "@example.org>", null, 900L, 12L));
            }
            return overviews;
        }

        Start find(long from) {
            return DateSearch.find(this, lowestNumber, highestNumber, from, DEPTH);
        }
    }

    @Test
    void theStartIsLessThanTheDepthBeforeTheFirstArticleOnOrAfterTheDate() {
        Group group = new Group(1, 10_000);

        Start start = group.find(6_200_000);

        assertThat(start.clampedToOldest()).isFalse();
        assertThat(start.number()).isLessThanOrEqualTo(6_200).isGreaterThan(6_200 - DEPTH);
    }

    @Test
    void everyDateOfALargeGroupIsFoundWithFewProbes() {
        Group group = new Group(1_000, 5_000_000);

        for (long wantedNumber : new long[] {1_001, 1_500, 77_777, 2_500_000, 4_999_999}) {
            group.probes.clear();

            Start start = group.find(wantedNumber * 1_000);

            assertThat(start.number()).isLessThanOrEqualTo(wantedNumber).isGreaterThan(wantedNumber - DEPTH);
            assertThat(group.probes).hasSizeLessThanOrEqualTo(30);
            assertThat(group.probes).allSatisfy(probe -> {
                String[] ends = probe.split("-");
                assertThat(Long.parseLong(ends[1]) - Long.parseLong(ends[0])).isLessThan(DateSearch.WINDOW_SIZE);
            });
        }
    }

    @Test
    void aDateBeforeTheOldestArticleStartsAtThatArticleAndSaysSo() {
        Group group = new Group(400, 10_000);

        assertThat(group.find(5)).isEqualTo(new Start(400, true));
        assertThat(group.probes).containsExactly("400-419");
    }

    @Test
    void theDateOfTheOldestArticleItselfIsNotClamped() {
        Group group = new Group(400, 10_000);

        assertThat(group.find(400_000)).isEqualTo(new Start(400, false));
    }

    @Test
    void aDateAfterTheNewestArticleStartsWithinTheNewestArticles() {
        Group group = new Group(1, 10_000);

        Start start = group.find(99_000_000);

        assertThat(start.clampedToOldest()).isFalse();
        assertThat(start.number()).isLessThanOrEqualTo(10_000).isGreaterThan(10_000 - DEPTH);
    }

    @Test
    void aGroupWithFewerArticlesThanTheDepthIsReadFromItsStartOrFromTheFirstArticleOfTheDate() {
        Group group = new Group(1, 300);

        assertThat(group.find(250_000).number()).isBetween(1L, 250L);
        assertThat(group.probes).containsExactly("1-20");
    }

    @Test
    void emptyWindowsMoveTheWindowUp() {
        Group group = new Group(1, 10_000);
        // The first midpoint is 5,001; four windows from there on have no article.
        group.withoutArticles(5_001, 5_080);

        Start start = group.find(6_200_000);

        assertThat(group.probes).contains("5001-5020", "5021-5040", "5041-5060", "5061-5080", "5081-5100");
        assertThat(start.number()).isLessThanOrEqualTo(6_200).isGreaterThan(6_200 - DEPTH);
    }

    @Test
    void afterFiveEmptyWindowsTheSearchFails() {
        Group group = new Group(1, 10_000);
        group.withoutArticles(5_001, 5_100);

        assertThatThrownBy(() -> group.find(6_200_000)).isInstanceOf(DatesUnreadableException.class);
        assertThat(group.probes).endsWith("5081-5100");
    }

    @Test
    void aGroupWhoseOldestArticlesHaveNoDateFails() {
        Group group = new Group(1, 10_000);
        for (long number = 1; number <= 100; number++) {
            group.undatedNumbers.add(number);
        }

        assertThatThrownBy(() -> group.find(6_200_000)).isInstanceOf(DatesUnreadableException.class);
    }

    @Test
    void anOverviewWithoutADateIsPassedOver() {
        Group group = new Group(1, 10_000);
        group.undatedNumbers.add(5_001L);
        group.undatedNumbers.add(5_002L);

        Start start = group.find(6_200_000);

        assertThat(start.number()).isLessThanOrEqualTo(6_200).isGreaterThan(6_200 - DEPTH);
    }

    @Test
    void aWrongDateThatNoProbeLooksAtChangesNothing() {
        Group group = new Group(1, 10_000);
        Start expected = group.find(6_200_000);
        Group groupWithWrongDates = new Group(1, 10_000);
        // Not the first article of any window that is probed.
        groupWithWrongDates.otherDates.put(5_010L, 1L);
        groupWithWrongDates.otherDates.put(6_100L, 99_000_000_000L);

        assertThat(groupWithWrongDates.find(6_200_000)).isEqualTo(expected);
    }

    @Test
    void aWrongDateAtAProbeStillGivesAStartInsideTheGroup() {
        Group group = new Group(1, 10_000);
        // The first midpoint: its article claims to be from the far future, so the search goes on below it.
        group.otherDates.put(5_001L, 99_000_000_000L);

        Start start = group.find(6_200_000);

        assertThat(start.number()).isBetween(1L, 10_000L);
        assertThat(start.clampedToOldest()).isFalse();
    }
}
