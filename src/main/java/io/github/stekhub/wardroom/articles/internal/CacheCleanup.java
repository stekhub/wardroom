package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.ArticleCache;
import io.github.stekhub.wardroom.settings.CacheSettings;
import io.github.stekhub.wardroom.settings.CacheUse;
import io.github.stekhub.wardroom.settings.Settings;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The cleanup job of the cache size limit (ADR 0023, ADR 0034).
 *
 * <ul>
 * <li>It runs at the start, after each synchronization run and after the settings were saved.</li>
 * <li>Above the limit, it deletes cached article content until the database is below 90 % of the limit.</li>
 * <li>Content of articles the server still has goes first, then the rest; oldest first within each.</li>
 * <li>With the limit switched off it deletes nothing.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CacheCleanup implements ArticleCache, CacheUse {

    /** Each batch is one short write, so that synchronization and reading go on between two of them. */
    static final int BATCH_SIZE = 1_000;

    private final CacheRepository cacheRepository;
    private final Settings settings;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    void cleanUpAtStart() {
        cleanUpSoon();
    }

    @Override
    public void cleanUpSoon() {
        requested.set(true);
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("cache-cleanup").start(() -> {
            try {
                while (requested.getAndSet(false)) {
                    cleanUp();
                }
            } catch (RuntimeException e) {
                log.error("The cache cleanup failed unexpectedly. It runs again after the next synchronization"
                        + " run.", e);
            } finally {
                running.set(false);
            }
            // A request that arrived while the loop was ending is not lost.
            if (requested.get()) {
                cleanUpSoon();
            }
        });
    }

    @Override
    public void settingsChanged() {
        cleanUpSoon();
    }

    @Override
    public long usedBytes() {
        return cacheRepository.usedBytes();
    }

    @Override
    public boolean limitUnreachable() {
        CacheSettings cacheSettings = settings.cache();
        return cacheSettings.limitEnabled() && limitUnreachable(cacheSettings.maxSizeBytes());
    }

    boolean limitUnreachable(long limitBytes) {
        return cacheRepository.usedBytes() > limitBytes && cacheRepository.findCleanable(false, 1).isEmpty();
    }

    /**
     * Checks the database against the limit of the settings and returns when the cleanup is done.
     *
     * @return the number of articles whose content was deleted
     */
    int cleanUp() {
        CacheSettings cacheSettings = settings.cache();
        if (!cacheSettings.limitEnabled()) {
            return 0;
        }
        return cleanUp(cacheSettings.maxSizeBytes(), BATCH_SIZE, () -> settings.cache().limitEnabled());
    }

    /** As {@link #cleanUp()}, with a limit in bytes that stays switched on, and with batches of another size. */
    int cleanUp(long limitBytes, int batchSize) {
        return cleanUp(limitBytes, batchSize, () -> true);
    }

    private int cleanUp(long limitBytes, int batchSize, BooleanSupplier limitStillEnabled) {
        long usedBytesBefore = cacheRepository.usedBytes();
        if (usedBytesBefore <= limitBytes) {
            return 0;
        }
        // Below 90 % of the limit, so that the cleanup does not run again after every synchronization.
        long targetBytes = limitBytes / 10 * 9;
        log.info("Cache cleanup started: the database uses {} bytes, the limit is {} bytes", usedBytesBefore,
                limitBytes);
        int cleanedArticles = 0;
        boolean targetReached = false;
        for (boolean stillOnServer : new boolean[] {true, false}) {
            while (!targetReached && limitStillEnabled.getAsBoolean()) {
                List<Integer> articleIds = cacheRepository.findCleanable(stillOnServer, batchSize);
                if (articleIds.isEmpty()) {
                    break;
                }
                cacheRepository.clearContent(articleIds);
                cleanedArticles += articleIds.size();
                targetReached = cacheRepository.usedBytes() < targetBytes;
            }
        }
        long usedBytesAfter = cacheRepository.usedBytes();
        if (usedBytesAfter > limitBytes && limitStillEnabled.getAsBoolean()) {
            log.warn("Cache cleanup ended: the content of {} articles was deleted, and the database still uses {}"
                    + " bytes. The stored headers alone are above the limit of {} bytes.", cleanedArticles,
                    usedBytesAfter, limitBytes);
        } else {
            log.info("Cache cleanup ended: the content of {} articles was deleted, the database uses {} bytes",
                    cleanedArticles, usedBytesAfter);
        }
        return cleanedArticles;
    }
}
