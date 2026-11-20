package io.github.stekhub.wardroom.articles.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The threading function of spec 006: articles and stubs by Message-ID, each with its parent.
 *
 * <p>The graph is first told which articles and stubs are stored and how they are linked. Then articles are
 * threaded one after the other, oldest first. Threading an article gives it the parent its own {@code References}
 * names, and creates a stub for every referenced Message-ID that is not stored. The parent of an article or stub
 * that was there before is never changed by another article.
 *
 * <p>The graph only computes. Storing the result is the task of its user.
 */
final class ThreadGraph {

    /** An article or a stub. */
    static final class Node {

        private final String messageId;
        private Long articleId;
        private boolean stub;
        private Node parent;

        private Node(String messageId, Long articleId, boolean stub) {
            this.messageId = messageId;
            this.articleId = articleId;
            this.stub = stub;
        }

        String messageId() {
            return messageId;
        }

        /** @return the ID of the stored row, or {@code null} for a stub that was created here and is not stored */
        Long articleId() {
            return articleId;
        }

        /** Tells the node under which ID its stub was stored. */
        void storedAs(long storedArticleId) {
            this.articleId = storedArticleId;
        }

        boolean isStub() {
            return stub;
        }

        /** @return the parent, or {@code null} for the first article of a thread */
        Node parent() {
            return parent;
        }
    }

    /**
     * What threading an article did.
     *
     * @param node         the article
     * @param cycleIgnored whether the link to its parent would have closed a cycle and was left out
     */
    record Threaded(Node node, boolean cycleIgnored) {
    }

    private final Map<String, Node> nodesByMessageId = new HashMap<>();
    private final List<Node> createdStubs = new ArrayList<>();

    /** Tells the graph about a stored article or stub. Its parent follows with {@link #link(String, String)}. */
    void addStored(String messageId, long articleId, boolean stub) {
        nodesByMessageId.putIfAbsent(messageId, new Node(messageId, articleId, stub));
    }

    /** Tells the graph the stored parent of a stored article or stub. Both must have been added. */
    void link(String messageId, String parentMessageId) {
        nodesByMessageId.get(messageId).parent = nodesByMessageId.get(parentMessageId);
    }

    /** @return the article or stub with this Message-ID, or {@code null} */
    Node find(String messageId) {
        return nodesByMessageId.get(messageId);
    }

    /** @return the stubs that threading created, each after the stub that is its parent */
    List<Node> createdStubs() {
        return List.copyOf(createdStubs);
    }

    /**
     * The Message-IDs that decide the place of an article: those of its {@code References}; without any, the first
     * one of its {@code In-Reply-To}, when that header is known (spec 006).
     *
     * @return the Message-IDs, oldest first; the last one is the parent
     */
    static List<String> ancestry(String messageId, String references, String inReplyTo) {
        List<String> referenced = ReferencesParser.parse(references, messageId);
        if (!referenced.isEmpty()) {
            return referenced;
        }
        List<String> repliedTo = ReferencesParser.parse(inReplyTo, messageId);
        return repliedTo.isEmpty() ? List.of() : List.of(repliedTo.getFirst());
    }

    /**
     * Gives a stored article the parent that its own headers name. A stub that was filled in is an article from
     * here on.
     *
     * @param messageId the Message-ID of the article; it must have been added
     * @param ancestry  the result of {@link #ancestry(String, String, String)} for the article
     */
    Threaded thread(String messageId, List<String> ancestry) {
        Node article = nodesByMessageId.get(messageId);
        article.stub = false;
        Node previous = null;
        for (String referencedMessageId : ancestry) {
            Node referenced = nodesByMessageId.get(referencedMessageId);
            if (referenced == null) {
                // The parent of a stub is the Message-ID listed before it; the first one has none.
                referenced = new Node(referencedMessageId, null, true);
                referenced.parent = previous;
                nodesByMessageId.put(referencedMessageId, referenced);
                createdStubs.add(referenced);
            }
            previous = referenced;
        }
        if (previous != null && isAncestorOrSelf(article, previous)) {
            article.parent = null;
            return new Threaded(article, true);
        }
        article.parent = previous;
        return new Threaded(article, false);
    }

    /** @return the topmost ancestor of the node; the node itself when it has no parent */
    Node rootOf(Node node) {
        Node current = node;
        // Stored links are not trusted to be free of cycles: the walk ends after as many steps as there are nodes.
        for (int step = 0; step < nodesByMessageId.size() && current.parent != null; step++) {
            current = current.parent;
        }
        return current;
    }

    /** @return whether the candidate is the node itself or one of the ancestors of {@code start} */
    private boolean isAncestorOrSelf(Node candidate, Node start) {
        Node current = start;
        for (int step = 0; step <= nodesByMessageId.size() && current != null; step++) {
            if (current == candidate) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }
}
