package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.articles.Threads;
import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpGroupStatus;
import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.sync.internal.SyncedArticleRepository.StoredChunk;
import io.github.stekhub.wardroom.sync.internal.SyncedArticleRepository.SyncedRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * One synchronization of one group on one connection (spec 005, "Synchronizing one group" and "Expiry and
 * renumbering").
 *
 * <p>Everything it knows about earlier work comes from the database, so a synchronization that was interrupted, and
 * an attempt that is repeated after a failure, continue after the last stored chunk. No database transaction is
 * open while the server is asked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GroupSynchronizer {

    /** Overviews fetched with one command and stored in one transaction (spec 005). */
    static final int CHUNK_SIZE = 1_000;

    private final SubscribedGroups subscribedGroups;
    private final SyncedArticleRepository syncedArticleRepository;
    private final TransactionOperations transactionOperations;
    private final Threads threads;

    /** How a synchronization ended. */
    enum End {
        /** Everything up to the high watermark is stored. */
        COMPLETED,
        /** Someone asked for the synchronization to stop; the chunks stored so far stay. */
        CANCELLED,
        /** The group is not subscribed any more, or was removed with its server. */
        NOT_SUBSCRIBED,
        /** The server has no group of this name ({@code 411}). */
        GROUP_UNAVAILABLE
    }

    /** What the synchronization did so far, over all its attempts, for the log. */
    static final class Progress {

        int overviewsFetched;
        int newArticles;
        int filledStubs;
        int expiredLocations;
        boolean renumbered;
    }

    /**
     * @param initialDepth    how many overviews a first synchronization fetches at most
     * @param cancelRequested asked before every chunk
     * @param progress        receives what was done
     * @throws io.github.stekhub.wardroom.nntp.NntpException when the server cannot be asked; the chunks stored
     *                                                       before stay
     */
    End synchronize(NntpClient client, SubscribedGroup group, int initialDepth, BooleanSupplier cancelRequested,
            Progress progress) {
        long groupId = group.groupId();
        if (cancelRequested.getAsBoolean()) {
            return End.CANCELLED;
        }
        if (subscribedGroups.find(groupId).isEmpty()) {
            return End.NOT_SUBSCRIBED;
        }
        Optional<NntpGroupStatus> selectedGroup = client.selectGroup(group.name());
        if (selectedGroup.isEmpty()) {
            return End.GROUP_UNAVAILABLE;
        }
        long lowWatermark = selectedGroup.get().lowWatermark();
        long highWatermark = selectedGroup.get().highWatermark();
        if (!storeGroupStatus(group, lowWatermark, highWatermark, progress)) {
            return End.NOT_SUBSCRIBED;
        }

        Optional<SyncedRange> newestRange = syncedArticleRepository.findNewestRange(groupId);
        long rangeContinuesAt = newestRange.map(range -> range.lastNumber() + 1)
                .orElse(Math.max(lowWatermark, highWatermark - initialDepth + 1));
        // Below the low watermark the server has nothing; asking for these numbers would only cost commands.
        long firstNumber = Math.max(1, Math.max(rangeContinuesAt, lowWatermark));
        for (long chunkFirst = firstNumber; chunkFirst <= highWatermark; chunkFirst += CHUNK_SIZE) {
            if (cancelRequested.getAsBoolean()) {
                return End.CANCELLED;
            }
            long chunkLast = Math.min(chunkFirst + CHUNK_SIZE - 1, highWatermark);
            List<NntpOverview> overviews = new ArrayList<>();
            client.overviews(chunkFirst, chunkLast, overviews::add);
            if (!storeChunk(groupId, chunkFirst, chunkLast, overviews, progress)) {
                return End.NOT_SUBSCRIBED;
            }
            threads.recomputeGroup(groupId);
        }
        if (firstNumber > highWatermark) {
            // Nothing was fetched. An article whose threading failed earlier gets its place now (spec 006).
            threads.recomputeGroup(groupId);
        }

        boolean recorded = Boolean.TRUE.equals(transactionOperations.execute(status -> {
            if (subscribedGroups.find(groupId).isEmpty()) {
                return false;
            }
            subscribedGroups.synchronizationSucceeded(groupId, System.currentTimeMillis());
            return true;
        }));
        return recorded ? End.COMPLETED : End.NOT_SUBSCRIBED;
    }

    /**
     * Stores the watermarks, and handles what they say about the stored articles: a low watermark that rose means
     * expired articles, and a high watermark below the end of the newest range means that the server has
     * renumbered the group. Then the cached articles of the group are removed, and what follows is a first
     * synchronization.
     *
     * @return {@code false} when the group is not subscribed any more
     */
    private boolean storeGroupStatus(SubscribedGroup group, long lowWatermark, long highWatermark,
            Progress progress) {
        long groupId = group.groupId();
        return Boolean.TRUE.equals(transactionOperations.execute(status -> {
            if (subscribedGroups.find(groupId).isEmpty()) {
                return false;
            }
            subscribedGroups.storeWatermarks(groupId, lowWatermark, highWatermark);
            Optional<SyncedRange> newestRange = syncedArticleRepository.findNewestRange(groupId);
            if (newestRange.isPresent() && highWatermark < newestRange.get().lastNumber()) {
                subscribedGroups.removeArticles(groupId);
                progress.renumbered = true;
                log.warn("The server has renumbered the group {} ({}): its highest article number is {}, and {} was"
                                + " synchronized already. The cached articles of the group were removed and are"
                                + " fetched again.", LogSafe.escape(group.name()), groupId, highWatermark,
                        newestRange.get().lastNumber());
            } else {
                progress.expiredLocations += syncedArticleRepository.markExpired(groupId, lowWatermark,
                        System.currentTimeMillis());
            }
            return true;
        }));
    }

    /**
     * Stores the articles of a chunk and the extended range in one transaction.
     *
     * @return {@code false} when the group is not subscribed any more; nothing was stored then
     */
    private boolean storeChunk(long groupId, long chunkFirst, long chunkLast, List<NntpOverview> overviews,
            Progress progress) {
        return Boolean.TRUE.equals(transactionOperations.execute(status -> {
            if (subscribedGroups.find(groupId).isEmpty()) {
                return false;
            }
            StoredChunk storedChunk = syncedArticleRepository.storeOverviews(groupId, overviews);
            syncedArticleRepository.extendNewestRange(groupId, chunkFirst, chunkLast);
            progress.overviewsFetched += overviews.size();
            progress.newArticles += storedChunk.newArticles();
            progress.filledStubs += storedChunk.filledStubs();
            log.debug("Group {}: chunk {}-{} stored, {} overviews, {} new articles, {} stubs filled in, {} new"
                            + " locations, {} moved locations", groupId, chunkFirst, chunkLast, overviews.size(),
                    storedChunk.newArticles(), storedChunk.filledStubs(), storedChunk.newLocations(),
                    storedChunk.movedLocations());
            return true;
        }));
    }
}
