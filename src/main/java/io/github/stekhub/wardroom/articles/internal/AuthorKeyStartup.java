package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;

import io.github.stekhub.wardroom.articles.AuthorKey;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Gives the articles their author key that were stored before the key was stored with them (spec 010, "Data model
 * changes"). The key cannot be computed in a migration, which is plain SQL. Without such articles it does nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AuthorKeyStartup {

    /** How many articles get their key in one transaction, so that the write lock is held only briefly. */
    private static final int BATCH_SIZE = 1_000;

    private final DSLContext database;
    private final TransactionOperations transactionOperations;

    @EventListener(ApplicationReadyEvent.class)
    void fillMissingAuthorKeys() {
        try {
            long startedAt = System.currentTimeMillis();
            int filledArticles = 0;
            int lastArticleId = 0;
            while (true) {
                List<Record2<Integer, String>> articles = findArticlesWithoutKey(lastArticleId);
                if (articles.isEmpty()) {
                    break;
                }
                lastArticleId = articles.getLast().value1();
                filledArticles += Boolean.TRUE.equals(transactionOperations.execute(status -> store(articles)))
                        ? articles.size() : 0;
            }
            if (filledArticles > 0) {
                log.info("Author keys were computed for {} articles in {} ms", filledArticles,
                        System.currentTimeMillis() - startedAt);
            }
        } catch (RuntimeException e) {
            log.error("The author keys of the stored articles could not be computed. Their authors cannot be "
                    + "ignored until the next start.", e);
        }
    }

    /** The next articles after the given one that have a {@code From} and no key, by ID. */
    private List<Record2<Integer, String>> findArticlesWithoutKey(int afterArticleId) {
        return database.select(ARTICLE.ID, ARTICLE.FROM)
                .from(ARTICLE)
                .where(ARTICLE.ID.gt(afterArticleId))
                .and(ARTICLE.AUTHOR_KEY.isNull())
                .and(ARTICLE.IS_STUB.isFalse())
                .and(ARTICLE.FROM.isNotNull())
                .orderBy(ARTICLE.ID)
                .limit(BATCH_SIZE)
                .fetch();
    }

    private boolean store(List<Record2<Integer, String>> articles) {
        for (Record2<Integer, String> article : articles) {
            database.update(ARTICLE)
                    .set(ARTICLE.AUTHOR_KEY, AuthorKey.of(article.value2()))
                    .where(ARTICLE.ID.eq(article.value1()))
                    // A synchronization may have filled the article in meanwhile.
                    .and(ARTICLE.AUTHOR_KEY.isNull())
                    .execute();
        }
        return true;
    }
}
