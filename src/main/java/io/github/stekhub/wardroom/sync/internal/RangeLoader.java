package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.articles.Threads;
import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpGroupStatus;
import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.sync.internal.DateSearch.DatesUnreadableException;
import io.github.stekhub.wardroom.sync.internal.SyncedArticleRepository.StoredChunk;
import io.github.stekhub.wardroom.sync.internal.SyncedArticleRepository.SyncedRange;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupNotSubscribedException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupNotSynchronizedException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupRenumberedException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.RangeNotFoundException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.ServerFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Loads articles the owner goes looking for, on one connection: "load older" and jump to date (spec 005;
 * ADR 0021, ADR 0033).
 *
 * <ul>
 * <li>Everything a step fetches is stored in one transaction, together with the range. A step that fails stores
 * nothing. No transaction is open while the server is asked.</li>
 * <li>Articles that are newly stored arrive read (ADR 0022).</li>
 * <li>Nothing is loaded above the newest range. What is there is new, and the synchronization stores it unread.</li>
 * </ul>
 *
 * <p>The caller makes sure that the group is neither synchronized nor unsubscribed meanwhile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RangeLoader {

    private static final String GROUP_UNAVAILABLE = "The group is not available on the server.";
    private static final String DATES_UNREADABLE = "The dates of the articles in this group could not be read.";

    private final SubscribedGroups subscribedGroups;
    private final SyncedArticleRepository syncedArticleRepository;
    private final TransactionOperations transactionOperations;
    private final Threads threads;

    /**
     * A range after a step.
     *
     * @param atOldest whether nothing older can be loaded: the range starts at the low watermark of the server
     * @param atNewest whether the range is the newest of the group
     */
    record LoadedRange(long rangeId, long firstNumber, long lastNumber, boolean atOldest, boolean atNewest) {
    }

    /**
     * @param range      the range that ends the view, or {@code null} when the time is not after the oldest article
     *                   of the server
     * @param oldestDate the date of the oldest article of the server, when there is no range; otherwise
     *                   {@code null}
     */
    record Jump(LoadedRange range, Long oldestDate) {
    }

    /** What the server and the database say about the group when a step begins. */
    private record GroupState(long lowestNumber, SyncedRange newestRange) {
    }

    /**
     * Extends the range downwards by up to {@code depth} article numbers. The step stops at the low watermark and
     * at the neighboring range, and merges with that range.
     *
     * @throws io.github.stekhub.wardroom.nntp.NntpException when the server cannot be asked; nothing is stored
     */
    LoadedRange extend(NntpClient client, SubscribedGroup group, long rangeId, int depth) {
        long groupId = group.groupId();
        GroupState groupState = selectGroup(client, group);
        SyncedRange range = syncedArticleRepository.findRange(groupId, rangeId)
                .orElseThrow(RangeNotFoundException::new);
        long neighborEnd = syncedArticleRepository.findRangeBelow(groupId, range.firstNumber())
                .map(SyncedRange::lastNumber).orElse(0L);
        long fetchFirst = Math.max(Math.max(range.firstNumber() - depth, groupState.lowestNumber()),
                neighborEnd + 1);
        long fetchLast = range.firstNumber() - 1;
        if (fetchFirst > fetchLast) {
            return loadedRange(groupId, range, groupState);
        }
        List<NntpOverview> overviews = fetch(client, fetchFirst, fetchLast);
        SyncedRange grownRange = transactionOperations.execute(status -> {
            requireSubscribed(groupId);
            StoredChunk storedChunk = syncedArticleRepository.storeOverviews(groupId, overviews,
                    System.currentTimeMillis());
            SyncedRange storedRange = syncedArticleRepository.growRange(groupId, rangeId, fetchFirst,
                    range.lastNumber());
            logStep(group, "Load older", fetchFirst, fetchLast, overviews.size(), storedChunk, storedRange);
            return storedRange;
        });
        threads.recomputeGroup(groupId);
        return loadedRange(groupId, grownRange, groupState);
    }

    /**
     * Finds the last article before the time and fetches up to {@code depth} article numbers that end there,
     * unless that article lies in a range already.
     *
     * @param until the time before which articles are shown, in UTC milliseconds
     * @throws io.github.stekhub.wardroom.nntp.NntpException when the server cannot be asked; nothing is stored
     */
    Jump jump(NntpClient client, SubscribedGroup group, long until, int depth) {
        long groupId = group.groupId();
        GroupState groupState = selectGroup(client, group);
        long highestNumber = groupState.newestRange().lastNumber();
        // When the server has none of the synchronized articles any more, what is stored is all there is to show.
        long endNumber = highestNumber;
        if (groupState.lowestNumber() <= highestNumber) {
            DateSearch.End end;
            try {
                end = DateSearch.find((firstNumber, lastNumber) -> fetch(client, firstNumber, lastNumber),
                        groupState.lowestNumber(), highestNumber, until);
            } catch (DatesUnreadableException e) {
                throw new ServerFailedException(DATES_UNREADABLE);
            }
            if (end.beforeOldest()) {
                return new Jump(null, end.oldestDate());
            }
            endNumber = end.number();
        }
        SyncedRange range;
        Optional<SyncedRange> rangeOfEnd = syncedArticleRepository.findRangeContaining(groupId, endNumber);
        if (rangeOfEnd.isPresent()) {
            range = rangeOfEnd.get();
        } else {
            long neighborEnd = syncedArticleRepository.findRangeBelow(groupId, endNumber)
                    .map(SyncedRange::lastNumber).orElse(0L);
            long fetchFirst = Math.max(Math.max(endNumber - depth + 1, groupState.lowestNumber()),
                    neighborEnd + 1);
            long fetchLast = endNumber;
            List<NntpOverview> overviews = fetch(client, fetchFirst, fetchLast);
            range = transactionOperations.execute(status -> {
                requireSubscribed(groupId);
                StoredChunk storedChunk = syncedArticleRepository.storeOverviews(groupId, overviews,
                        System.currentTimeMillis());
                long rangeId = syncedArticleRepository.insertRange(groupId, fetchFirst, fetchLast);
                SyncedRange storedRange = syncedArticleRepository.growRange(groupId, rangeId, fetchFirst,
                        fetchLast);
                logStep(group, "Jump to date", fetchFirst, fetchLast, overviews.size(), storedChunk, storedRange);
                return storedRange;
            });
            threads.recomputeGroup(groupId);
        }
        return new Jump(loadedRange(groupId, range, groupState), null);
    }

    private GroupState selectGroup(NntpClient client, SubscribedGroup group) {
        NntpGroupStatus groupStatus = client.selectGroup(group.name())
                .orElseThrow(() -> new ServerFailedException(GROUP_UNAVAILABLE));
        SyncedRange newestRange = syncedArticleRepository.findNewestRange(group.groupId())
                .orElseThrow(GroupNotSynchronizedException::new);
        if (groupStatus.highWatermark() < newestRange.lastNumber()) {
            throw new GroupRenumberedException();
        }
        return new GroupState(Math.max(1, groupStatus.lowWatermark()), newestRange);
    }

    /** All overviews of the numbers, fetched in chunks and held in memory until the step stores them. */
    private static List<NntpOverview> fetch(NntpClient client, long firstNumber, long lastNumber) {
        List<NntpOverview> overviews = new ArrayList<>();
        for (long chunkFirst = firstNumber; chunkFirst <= lastNumber; chunkFirst += GroupSynchronizer.CHUNK_SIZE) {
            long chunkLast = Math.min(chunkFirst + GroupSynchronizer.CHUNK_SIZE - 1, lastNumber);
            client.overviews(chunkFirst, chunkLast, overviews::add);
        }
        return overviews;
    }

    /** The server may have been removed, or the group unsubscribed by a removal, since the step began. */
    private void requireSubscribed(long groupId) {
        if (subscribedGroups.find(groupId).isEmpty()) {
            throw new GroupNotSubscribedException();
        }
    }

    private LoadedRange loadedRange(long groupId, SyncedRange range, GroupState groupState) {
        boolean newest = syncedArticleRepository.findNewestRange(groupId)
                .map(newestRange -> newestRange.id() == range.id())
                .orElse(true);
        return new LoadedRange(range.id(), range.firstNumber(), range.lastNumber(),
                range.firstNumber() <= groupState.lowestNumber(), newest);
    }

    private static void logStep(SubscribedGroup group, String step, long fetchFirst, long fetchLast, int overviewCount,
            StoredChunk storedChunk, SyncedRange storedRange) {
        log.info("{} in group {} ({}): numbers {}-{} fetched, {} overviews, {} new articles, {} stubs filled in;"
                        + " the range is now {}-{}", step, LogSafe.escape(group.name()), group.groupId(), fetchFirst,
                fetchLast, overviewCount, storedChunk.newArticles(), storedChunk.filledStubs(),
                storedRange.firstNumber(), storedRange.lastNumber());
    }
}
