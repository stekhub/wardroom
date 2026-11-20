package io.github.stekhub.wardroom.articles.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The subject fallback of spec 006, for the articles of one group: an article without references whose subject
 * starts with a reply prefix attaches to the newest earlier thread root with the same subject.
 *
 * <p>The articles are decided oldest first, and each decision uses only what was decided before it. So the result
 * is the same on every run and does not depend on the order in which the articles were fetched.
 */
final class SubjectFallback {

    /** A thread root is matched when it was posted at most this long before the article. */
    static final long MAX_AGE_MILLIS = Duration.ofDays(30).toMillis();

    private SubjectFallback() {
    }

    /**
     * A dated article of the group that is not a stub, and has no references or no parent.
     *
     * @param parentId          the parent the article has now, or {@code null}
     * @param withoutReferences whether the article has neither {@code References} nor a known {@code In-Reply-To}
     * @param decidedHere       whether this group decides the place of the article. A crossposted article is
     *                          decided in one of its groups only; in the others it keeps the parent it has
     */
    record Candidate(long articleId, String messageId, String subject, long date, Long parentId,
                     boolean withoutReferences, boolean decidedHere) {

        private boolean usesFallback() {
            return withoutReferences && decidedHere && SubjectNormalizer.hasReplyPrefix(subject);
        }
    }

    /**
     * @param articleId the article the fallback applies to
     * @param parentId  the thread root the article attaches to, or {@code null} when it is its own thread root
     */
    record Decision(long articleId, Long parentId) {
    }

    /** @return one decision for every article the fallback applies to, oldest article first */
    static List<Decision> resolve(List<Candidate> candidates) {
        List<Candidate> oldestFirst = new ArrayList<>(candidates);
        oldestFirst.sort(Comparator.comparingLong(Candidate::date).thenComparing(Candidate::messageId));
        // Roots are added oldest first, so the one kept for a subject is the newest so far.
        Map<String, Candidate> newestRootBySubject = new HashMap<>();
        List<Decision> decisions = new ArrayList<>();
        for (Candidate candidate : oldestFirst) {
            String subject = SubjectNormalizer.normalize(candidate.subject());
            boolean root;
            if (candidate.usesFallback()) {
                Candidate earlierRoot = subject.isEmpty() ? null : newestRootBySubject.get(subject);
                boolean matched = earlierRoot != null && candidate.date() - earlierRoot.date() <= MAX_AGE_MILLIS;
                decisions.add(new Decision(candidate.articleId(), matched ? earlierRoot.articleId() : null));
                root = !matched;
            } else {
                root = candidate.parentId() == null;
            }
            if (root && !subject.isEmpty()) {
                newestRootBySubject.put(subject, candidate);
            }
        }
        return decisions;
    }
}
