package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.nntp.NntpOverview;
import java.util.List;
import java.util.Optional;

/**
 * Finds the article number up to which a group is shown for a date (spec 005, "Date search"; ADR 0021,
 * ADR 0033).
 *
 * <p>NNTP cannot ask for articles by date. Article numbers are given in the order of arrival, so they serve as the
 * time axis: a binary search over the numbers looks at the date of a few overviews at each midpoint. The result is
 * approximate, because the date of an article is written by its author and can be wrong.
 */
final class DateSearch {

    /** How many article numbers one probe asks for. */
    static final int WINDOW_SIZE = 20;

    /** After this many windows in a row without a usable date, the search gives up. */
    static final int MAX_EMPTY_WINDOWS = 5;

    private DateSearch() {
    }

    /** Fetches the overviews of a range of article numbers. They are looked at and not stored. */
    @FunctionalInterface
    interface OverviewWindows {

        List<NntpOverview> fetch(long firstNumber, long lastNumber);
    }

    /**
     * @param number       the highest article number to show: the articles before the time have this number or a
     *                     lower one, and fewer than {@link #WINDOW_SIZE} numbers up to it are from the time on
     * @param beforeOldest whether the time is not after the oldest article, so there is nothing to show
     * @param oldestDate   the date of the oldest article that has one, in UTC milliseconds
     */
    record End(long number, boolean beforeOldest, long oldestDate) {
    }

    /** No date could be read where the search needed one. */
    static class DatesUnreadableException extends RuntimeException {

        DatesUnreadableException() {
            super("The dates of the articles could not be read");
        }
    }

    /**
     * @param lowestNumber  the lowest article number to consider; not above the highest
     * @param highestNumber the highest article number to consider
     * @param until         the time before which articles are shown, in UTC milliseconds
     * @throws DatesUnreadableException when five windows in a row have no overview with a date, or the oldest
     *                                  articles have none
     */
    static End find(OverviewWindows overviewWindows, long lowestNumber, long highestNumber, long until) {
        NntpOverview oldestOverview = firstDatedOverview(overviewWindows, lowestNumber, highestNumber)
                .orElseThrow(DatesUnreadableException::new);
        if (oldestOverview.date() >= until) {
            return new End(lowestNumber, true, oldestOverview.date());
        }
        // The first article from the time on lies between lowerEnd and upperEnd + 1.
        long lowerEnd = oldestOverview.number() + 1;
        long upperEnd = highestNumber;
        while (upperEnd - lowerEnd + 1 >= WINDOW_SIZE) {
            long midpoint = lowerEnd + (upperEnd - lowerEnd) / 2;
            Optional<NntpOverview> probe = firstDatedOverview(overviewWindows, midpoint, highestNumber);
            if (probe.isEmpty() || probe.get().date() >= until) {
                upperEnd = midpoint - 1;
            } else {
                lowerEnd = probe.get().number() + 1;
            }
        }
        return new End(upperEnd, false, oldestOverview.date());
    }

    /**
     * The first overview with a date in the window that starts at the given number. An empty window, of cancelled
     * or missing articles, moves the window up.
     *
     * @return the overview, or empty when the windows reached the highest number without finding one
     * @throws DatesUnreadableException after five windows without a date
     */
    private static Optional<NntpOverview> firstDatedOverview(OverviewWindows overviewWindows, long startNumber,
            long highestNumber) {
        long windowFirst = startNumber;
        for (int window = 1; window <= MAX_EMPTY_WINDOWS; window++) {
            if (windowFirst > highestNumber) {
                return Optional.empty();
            }
            long windowLast = Math.min(windowFirst + WINDOW_SIZE - 1, highestNumber);
            Optional<NntpOverview> datedOverview = overviewWindows.fetch(windowFirst, windowLast).stream()
                    .filter(overview -> overview.date() != null)
                    .findFirst();
            if (datedOverview.isPresent()) {
                return datedOverview;
            }
            windowFirst = windowLast + 1;
        }
        throw new DatesUnreadableException();
    }
}
