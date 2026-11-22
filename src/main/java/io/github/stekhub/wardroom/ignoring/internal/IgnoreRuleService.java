package io.github.stekhub.wardroom.ignoring.internal;

import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreFailures.ArticleNotFoundException;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleRepository.RuleSource;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleRepository.StoredRule;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The ignore rules of the owner (spec 010). A rule is a filter: creating or removing one changes no article and no
 * read state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class IgnoreRuleService {

    /** The label of a thread none of whose known articles has a subject. */
    static final String NO_SUBJECT = "(no subject)";

    private final IgnoreRuleRepository ignoreRuleRepository;

    /** What a rule ignores. */
    enum Kind {
        AUTHOR, THREAD
    }

    List<StoredRule> rules() {
        return ignoreRuleRepository.findAll();
    }

    /**
     * Ignores the author of the article, or the thread of the article.
     *
     * @return the rule; when the author or the thread is ignored already, the rule that does so, unchanged
     * @throws ArticleNotFoundException when no article has this ID
     * @throws FieldValidationException when the author of a stub or of an article without an author is to be
     *                                  ignored
     */
    StoredRule ignore(Kind kind, long articleId) {
        RuleSource ruleSource = ignoreRuleRepository.findRuleSource(articleId)
                .orElseThrow(ArticleNotFoundException::new);
        StoredRule rule = switch (kind) {
            case AUTHOR -> ignoreAuthor(ruleSource);
            case THREAD -> ignoreThread(ruleSource);
        };
        log.info("Ignore rule {} of kind {} is in place", rule.id(), rule.kind());
        return rule;
    }

    /** Removes the rule. Nothing happens when it is removed already. */
    void remove(long ruleId) {
        ignoreRuleRepository.delete(ruleId);
        log.info("Ignore rule {} is removed", ruleId);
    }

    private StoredRule ignoreAuthor(RuleSource ruleSource) {
        if (ruleSource.authorKey() == null) {
            throw new FieldValidationException("articleId", "This article has no known author.");
        }
        return ignoreRuleRepository.store(IgnoreFilters.AUTHOR, ruleSource.authorKey(),
                AuthorName.of(ruleSource.from()), System.currentTimeMillis());
    }

    private StoredRule ignoreThread(RuleSource ruleSource) {
        // The root can have changed since an earlier rule was created; the thread is ignored all the same.
        Optional<StoredRule> ruleOfThread = ignoreRuleRepository.findThreadRule(ruleSource.threadRootId());
        if (ruleOfThread.isPresent()) {
            return ruleOfThread.get();
        }
        // A root that is a stub has no subject; the article the owner chose stands in.
        String label = firstText(ruleSource.rootSubject(), ruleSource.subject());
        return ignoreRuleRepository.store(IgnoreFilters.THREAD, ruleSource.rootMessageId(), label,
                System.currentTimeMillis());
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return NO_SUBJECT;
    }
}
