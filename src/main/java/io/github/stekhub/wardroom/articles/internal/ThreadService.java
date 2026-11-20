package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.Threads;
import io.github.stekhub.wardroom.articles.internal.SubjectFallback.Decision;
import io.github.stekhub.wardroom.articles.internal.ThreadGraph.Node;
import io.github.stekhub.wardroom.articles.internal.ThreadGraph.Threaded;
import io.github.stekhub.wardroom.articles.internal.ThreadRepository.Child;
import io.github.stekhub.wardroom.articles.internal.ThreadRepository.Place;
import io.github.stekhub.wardroom.articles.internal.ThreadRepository.StoredNode;
import io.github.stekhub.wardroom.articles.internal.ThreadRepository.UnthreadedArticle;
import io.github.stekhub.wardroom.groups.ThreadRecomputation;
import io.github.stekhub.wardroom.platform.ProtocolAnomaly;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyKind;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Computes the place of articles in their threads and stores it (spec 006).
 *
 * <ul>
 * <li>An article without a thread root has no place yet. New articles are stored that way, and a stub that is
 * filled in loses its thread root. Threading a group gives these articles their place, by article number, oldest
 * first.</li>
 * <li>Threading an article never changes the parent of another article or stub. When the article had replies
 * already, as a stub, their thread root follows.</li>
 * <li>Before the articles of a group are removed, the threads they are in are taken apart; afterwards the articles
 * that remain are threaded again. Threads without an article in the group are not touched.</li>
 * <li>A complete recomputation deletes all stubs and threads every article again, group by group. Its result does
 * not depend on the order in which the articles were fetched.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ThreadService implements Threads, ThreadRecomputation {

    /** How many articles are threaded at once. All of them and their ancestors are held in memory. */
    static final int PAGE_SIZE = 1_000;

    private final ThreadRepository threadRepository;
    private final TransactionOperations transactionOperations;

    @Override
    public void recomputeGroup(long groupId) {
        try {
            transactionOperations.executeWithoutResult(status -> recompute(List.of(groupId)));
        } catch (RuntimeException e) {
            log.error("The threads of group {} could not be recomputed. The articles keep the place they had; the"
                    + " next synchronization tries again.", groupId, e);
        }
    }

    /**
     * Gives one article its place again, from its own headers, after its content was fetched: a stub that was
     * filled in, or an article whose {@code In-Reply-To} is known now (spec 006, spec 007). The article does not
     * have to be located in a group. Runs in a transaction of its own and never throws.
     */
    void rethreadArticle(long articleId) {
        try {
            transactionOperations.executeWithoutResult(status -> {
                ProtocolAnomalyLog anomalyLog = new ProtocolAnomalyLog(log);
                threadRepository.forgetPlace(articleId);
                threadArticles(threadRepository.findUnthreadedArticle(articleId).stream().toList(), anomalyLog);
                for (long groupId : threadRepository.findGroupsOf(articleId)) {
                    applySubjectFallback(groupId);
                }
                anomalyLog.finish();
            });
        } catch (RuntimeException e) {
            log.error("The thread of article {} could not be recomputed. The article keeps the place it had.",
                    articleId, e);
        }
    }

    @Override
    public void forgetThreadsOfGroup(long groupId) {
        transactionOperations.executeWithoutResult(status -> {
            // An article of the group that has no place yet gets it first, so the thread it is in is known.
            recompute(List.of(groupId));
            threadRepository.forgetThreadsOfGroup(groupId);
        });
    }

    @Override
    public void threadArticlesWithoutPlace() {
        transactionOperations.executeWithoutResult(status -> {
            long startedAt = System.currentTimeMillis();
            threadRepository.deleteStubsWithoutPlace();
            int threadedArticles = recompute(threadRepository.findGroupsWithUnthreadedArticles());
            if (threadedArticles > 0) {
                log.info("Threads were recomputed after articles were removed: {} articles in {} ms",
                        threadedArticles, System.currentTimeMillis() - startedAt);
            }
        });
    }

    @Override
    public void recomputeAllThreads() {
        transactionOperations.executeWithoutResult(status -> {
            long startedAt = System.currentTimeMillis();
            threadRepository.deleteStubs();
            threadRepository.forgetPlaces();
            int threadedArticles = recompute(threadRepository.findGroupsWithArticles());
            if (threadedArticles > 0) {
                log.info("All threads were recomputed: {} articles in {} ms", threadedArticles,
                        System.currentTimeMillis() - startedAt);
            }
        });
    }

    /**
     * Threads the articles of the groups that have no place, lowest group first, and then evaluates the subject
     * fallback of each group.
     *
     * @return how many articles were threaded
     */
    private int recompute(List<Long> groupIds) {
        ProtocolAnomalyLog anomalyLog = new ProtocolAnomalyLog(log);
        int threadedArticles = 0;
        for (long groupId : groupIds) {
            int threadedInPage;
            do {
                threadedInPage = threadPage(groupId, anomalyLog);
                threadedArticles += threadedInPage;
            } while (threadedInPage > 0);
        }
        for (long groupId : groupIds) {
            applySubjectFallback(groupId);
        }
        anomalyLog.finish();
        return threadedArticles;
    }

    /** @return how many articles got their place; 0 when the group has none left without one */
    private int threadPage(long groupId, ProtocolAnomalyLog anomalyLog) {
        return threadArticles(threadRepository.findUnthreaded(groupId, PAGE_SIZE), anomalyLog);
    }

    /** @return how many articles got their place */
    private int threadArticles(List<UnthreadedArticle> unthreadedArticles, ProtocolAnomalyLog anomalyLog) {
        if (unthreadedArticles.isEmpty()) {
            return 0;
        }
        Map<Long, List<String>> ancestryByArticleId = new HashMap<>();
        Set<String> messageIds = new HashSet<>();
        for (UnthreadedArticle article : unthreadedArticles) {
            // In-Reply-To is known only when the content of an article has been fetched (spec 007).
            List<String> ancestry = ThreadGraph.ancestry(article.messageId(), article.references(),
                    article.inReplyTo());
            ancestryByArticleId.put(article.articleId(), ancestry);
            messageIds.add(article.messageId());
            messageIds.addAll(ancestry);
        }
        ThreadGraph threadGraph = graphOf(threadRepository.findWithAncestors(messageIds));

        List<Node> threadedNodes = new ArrayList<>();
        for (UnthreadedArticle article : unthreadedArticles) {
            Threaded threaded = threadGraph.thread(article.messageId(), ancestryByArticleId.get(article.articleId()));
            threadedNodes.add(threaded.node());
            if (threaded.cycleIgnored()) {
                anomalyLog.report(ProtocolAnomaly.builder()
                        .kind(ProtocolAnomalyKind.REFERENCES_CYCLE)
                        .messageId(article.messageId())
                        .action("link-ignored")
                        .build());
            }
        }

        // A stub is created after the stub that is its parent, so the parent has its ID by then.
        for (Node stub : threadGraph.createdStubs()) {
            stub.storedAs(threadRepository.insertStub(stub.messageId(), articleIdOf(stub.parent())));
        }
        List<Place> places = new ArrayList<>();
        Map<Long, Long> threadRootByArticleId = new HashMap<>();
        for (Node stub : threadGraph.createdStubs()) {
            places.add(placeOf(stub, threadGraph));
        }
        for (Node article : threadedNodes) {
            Place place = placeOf(article, threadGraph);
            places.add(place);
            threadRootByArticleId.put(place.articleId(), place.threadRootId());
        }
        threadRepository.updatePlaces(places);
        // An article that was a stub can have replies; they belong to the thread the article is in now.
        moveReplies(threadRootByArticleId);
        return unthreadedArticles.size();
    }

    private static ThreadGraph graphOf(List<StoredNode> storedNodes) {
        ThreadGraph threadGraph = new ThreadGraph();
        Map<Long, String> messageIdByArticleId = new HashMap<>();
        for (StoredNode storedNode : storedNodes) {
            threadGraph.addStored(storedNode.messageId(), storedNode.articleId(), storedNode.stub());
            messageIdByArticleId.put(storedNode.articleId(), storedNode.messageId());
        }
        for (StoredNode storedNode : storedNodes) {
            String parentMessageId = storedNode.parentId() == null
                    ? null
                    : messageIdByArticleId.get(storedNode.parentId());
            if (parentMessageId != null) {
                threadGraph.link(storedNode.messageId(), parentMessageId);
            }
        }
        return threadGraph;
    }

    private static Place placeOf(Node node, ThreadGraph threadGraph) {
        return new Place(node.articleId(), articleIdOf(node.parent()), threadGraph.rootOf(node).articleId());
    }

    private static Long articleIdOf(Node node) {
        return node == null ? null : node.articleId();
    }

    /**
     * The subject fallback of the group (spec 006). It is evaluated completely every time, so a matching thread
     * root that was fetched later is found.
     */
    private void applySubjectFallback(long groupId) {
        List<SubjectFallback.Candidate> candidates = threadRepository.findFallbackCandidates(groupId);
        Map<Long, Long> parentByArticleId = new HashMap<>();
        for (SubjectFallback.Candidate candidate : candidates) {
            parentByArticleId.put(candidate.articleId(), candidate.parentId());
        }
        for (Decision decision : SubjectFallback.resolve(candidates)) {
            if (Objects.equals(decision.parentId(), parentByArticleId.get(decision.articleId()))) {
                continue;
            }
            threadRepository.setParent(decision.articleId(), decision.parentId());
            // The article attaches to a thread root, or is one itself.
            long threadRootId = decision.parentId() == null ? decision.articleId() : decision.parentId();
            threadRepository.updateThreadRoot(decision.articleId(), threadRootId);
            moveReplies(Map.of(decision.articleId(), threadRootId));
            log.debug("Subject fallback: article {} of group {} now has the parent {}", decision.articleId(),
                    groupId, decision.parentId());
        }
    }

    /**
     * Gives the replies below the articles, on every level, the thread root of their article. Replies without a
     * place are left alone; they get theirs when they are threaded.
     */
    private void moveReplies(Map<Long, Long> threadRootByArticleId) {
        Map<Long, Long> threadRootByParentId = new HashMap<>(threadRootByArticleId);
        Set<Long> visitedArticleIds = new HashSet<>(threadRootByArticleId.keySet());
        while (!threadRootByParentId.isEmpty()) {
            Map<Long, Long> threadRootByReplyId = new HashMap<>();
            for (Child reply : threadRepository.findThreadedChildren(threadRootByParentId.keySet())) {
                if (visitedArticleIds.add(reply.articleId())) {
                    threadRootByReplyId.put(reply.articleId(), threadRootByParentId.get(reply.parentId()));
                }
            }
            threadRepository.updateThreadRoots(threadRootByReplyId);
            threadRootByParentId = threadRootByReplyId;
        }
    }
}
