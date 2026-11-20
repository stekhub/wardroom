package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.ExtractedContent;
import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.OmittedPart;
import io.github.stekhub.wardroom.articles.internal.ArticleContentRepository.ArticleState;
import io.github.stekhub.wardroom.articles.internal.ArticleContentRepository.StoredContent;
import io.github.stekhub.wardroom.articles.internal.ArticleFailures.ArticleNotFoundException;
import io.github.stekhub.wardroom.articles.internal.ArticleFailures.ContentUnavailableException;
import io.github.stekhub.wardroom.articles.internal.ArticleFailures.ServerFailedException;
import io.github.stekhub.wardroom.nntp.NntpArticle;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.server.NntpServers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fetches, caches and returns the content of articles, and marks articles as read (spec 007).
 *
 * <ul>
 * <li>Cached content is never fetched again. Requests for the same article that arrive while it is being fetched
 * wait for that fetch and get its result.</li>
 * <li>The fetch uses the connection that is kept for interactive actions, with a single attempt. It runs outside
 * of a database transaction; the result is stored in one transaction.</li>
 * <li>The text of an article is never logged.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ArticleContentService {

    /** How many bytes of an article are read from the server at most. */
    static final int MAX_ARTICLE_BYTES = 10 * 1_048_576;

    private static final TypeReference<List<OmittedPart>> OMITTED_PARTS = new TypeReference<>() {
    };

    private final ArticleContentRepository articleContentRepository;
    private final NntpServers nntpServers;
    private final NntpConnections connections;
    private final ThreadService threadService;
    private final TransactionOperations transactionOperations;
    private final JsonMapper jsonMapper;

    private final Map<Long, CompletableFuture<ArticleContent>> runningFetches = new ConcurrentHashMap<>();

    /** The content of an article as the browser gets it. */
    record ArticleContent(long articleId, String body, boolean flowed, boolean delSp, boolean truncated,
                          List<OmittedPart> omittedParts, String rawHeaders) {
    }

    /**
     * @return the content, from the cache or fetched from the server
     * @throws ArticleNotFoundException    when no article has this ID
     * @throws ContentUnavailableException when the server has answered that it no longer has the article, now or
     *                                     at an earlier attempt; an earlier answer is not asked for again
     * @throws ServerFailedException       when the content could not be fetched
     */
    ArticleContent content(long articleId) {
        return content(articleId, false);
    }

    /** Like {@link #content(long)}, but asks the server again when it had no such article at an earlier attempt. */
    ArticleContent retry(long articleId) {
        return content(articleId, true);
    }

    /**
     * Marks the article as read. Reading it twice changes nothing.
     *
     * @throws ArticleNotFoundException when no article has this ID
     */
    void markRead(long articleId) {
        long now = System.currentTimeMillis();
        transactionOperations.executeWithoutResult(status -> {
            if (articleContentRepository.findState(articleId).isEmpty()) {
                throw new ArticleNotFoundException();
            }
            articleContentRepository.markRead(articleId, now);
        });
    }

    private ArticleContent content(long articleId, boolean askAgain) {
        ArticleState articleState = articleContentRepository.findState(articleId)
                .orElseThrow(ArticleNotFoundException::new);
        if (articleState.contentFetched()) {
            return cachedContent(articleId);
        }
        if (articleState.contentUnavailableAt() != null && !askAgain) {
            throw new ContentUnavailableException();
        }
        CompletableFuture<ArticleContent> ownFetch = new CompletableFuture<>();
        CompletableFuture<ArticleContent> runningFetch = runningFetches.putIfAbsent(articleId, ownFetch);
        if (runningFetch != null) {
            return resultOf(runningFetch);
        }
        try {
            ArticleContent articleContent = fetch(articleId);
            ownFetch.complete(articleContent);
            return articleContent;
        } catch (RuntimeException e) {
            ownFetch.completeExceptionally(e);
            throw e;
        } finally {
            runningFetches.remove(articleId, ownFetch);
        }
    }

    private static ArticleContent resultOf(CompletableFuture<ArticleContent> runningFetch) {
        try {
            return runningFetch.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw e;
        }
    }

    private ArticleContent fetch(long articleId) {
        // Another request may have stored the content between the first look and the start of this fetch.
        ArticleState articleState = articleContentRepository.findState(articleId)
                .orElseThrow(ArticleNotFoundException::new);
        if (articleState.contentFetched()) {
            return cachedContent(articleId);
        }
        Optional<NntpArticle> fetchedArticle = fetchFromServer(articleState);
        long now = System.currentTimeMillis();
        if (fetchedArticle.isEmpty()) {
            transactionOperations.executeWithoutResult(
                    status -> articleContentRepository.markUnavailable(articleId, now));
            log.info("The server no longer has article {}", articleId);
            throw new ContentUnavailableException();
        }
        NntpArticle article = fetchedArticle.get();
        ExtractedContent extractedContent = ArticleContentExtractor.extract(article.content(), article.complete());
        String omittedPartsJson = jsonMapper.writeValueAsString(extractedContent.omittedParts());
        boolean placeChanged = Boolean.TRUE.equals(transactionOperations.execute(status -> {
            articleContentRepository.storeContent(articleId, extractedContent, omittedPartsJson, now);
            boolean stubFilled = articleContentRepository.fillStub(articleId, extractedContent.headers(),
                    article.content().length);
            // In-Reply-To decides the place of an article that has no References (spec 006).
            boolean inReplyToDecides = !ReferencesParser.hasMessageIdToken(
                    articleContentRepository.findReferences(articleId))
                    && ReferencesParser.hasMessageIdToken(extractedContent.headers().inReplyTo());
            return stubFilled || inReplyToDecides;
        }));
        log.debug("The content of article {} was fetched: {} bytes, {} omitted parts, truncated={}", articleId,
                article.content().length, extractedContent.omittedParts().size(), extractedContent.truncated());
        if (placeChanged) {
            threadService.rethreadArticle(articleId);
        }
        return new ArticleContent(articleId, extractedContent.body(), extractedContent.flowed(),
                extractedContent.delSp(), extractedContent.truncated(), extractedContent.omittedParts(),
                extractedContent.rawHeaders());
    }

    private Optional<NntpArticle> fetchFromServer(ArticleState articleState) {
        long serverId = articleContentRepository.findServerId(articleState.articleId())
                .orElseThrow(() -> new ServerFailedException("The server of this article is not known."));
        if (nntpServers.credentialsInvalid(serverId)) {
            throw new ServerFailedException("Wardroom cannot log in to the server. Enter the username and "
                    + "password again in the server settings.");
        }
        try {
            NntpServerSettings serverSettings = nntpServers.connectionSettings(serverId)
                    .orElseThrow(() -> new ServerFailedException("The server of this article is not known."));
            return connections.interactiveWithoutRetry(serverSettings,
                    client -> client.article(articleState.messageId(), MAX_ARTICLE_BYTES));
        } catch (NntpException e) {
            if (e.type() == NntpFailureType.AUTH_FAILED) {
                nntpServers.markCredentialsInvalid(serverId);
            }
            log.warn("Fetching the content of article {} failed: {} ({})", articleState.articleId(), e.type(),
                    e.getMessage());
            throw new ServerFailedException(e.getMessage());
        }
    }

    private ArticleContent cachedContent(long articleId) {
        StoredContent storedContent = articleContentRepository.findContent(articleId)
                .orElseThrow(ArticleNotFoundException::new);
        List<OmittedPart> omittedParts = storedContent.omittedPartsJson() == null
                ? List.of()
                : jsonMapper.readValue(storedContent.omittedPartsJson(), OMITTED_PARTS);
        return new ArticleContent(articleId, storedContent.body(), storedContent.flowed(), storedContent.delSp(),
                storedContent.truncated(), omittedParts, storedContent.rawHeaders());
    }
}
