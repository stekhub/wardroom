package io.github.stekhub.wardroom.ignoring.internal;

import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import io.github.stekhub.wardroom.ignoring.SubjectWords;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreFailures.ArticleNotFoundException;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreFailures.GroupNotFoundException;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleRepository.RuleGroup;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleRepository.RuleSource;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleRepository.StoredRule;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The ignore rules of the owner (spec 010, spec 022, spec 023). A rule is a filter: creating or removing one changes
 * no article and no read state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class IgnoreRuleService {

    /** The label of a thread none of whose known articles has a subject. */
    static final String NO_SUBJECT = "(no subject)";

    static final int SUBJECT_TEXT_MAX_LENGTH = 100;

    private final IgnoreRuleRepository ignoreRuleRepository;

    /** What a rule ignores. */
    enum Kind {
        AUTHOR, THREAD, GROUP, SUBJECT
    }

    List<StoredRule> rules() {
        return ignoreRuleRepository.findAll();
    }

    /**
     * Creates a rule.
     *
     * @param articleId for {@code AUTHOR} and {@code THREAD}: an article by the author or of the thread
     * @param groupId   for {@code GROUP}: the group; for {@code SUBJECT}: the group the rule is for, or
     *                  {@code null} for all groups
     * @param text      for {@code SUBJECT}: the word or phrase
     * @return the rule; when the same is ignored already, the rule that does so, unchanged
     * @throws ArticleNotFoundException when no article has this ID
     * @throws GroupNotFoundException   when no group has this ID
     * @throws FieldValidationException when a value is missing or breaks a rule
     */
    StoredRule ignore(Kind kind, Long articleId, Long groupId, String text) {
        StoredRule rule = switch (kind) {
            case AUTHOR -> ignoreAuthor(ruleSource(articleId));
            case THREAD -> ignoreThread(ruleSource(articleId));
            case GROUP -> ignoreGroup(groupId);
            case SUBJECT -> ignoreSubject(text, groupId);
        };
        log.info("Ignore rule {} of kind {} is in place", rule.id(), rule.kind());
        return rule;
    }

    /** Removes the rule. Nothing happens when it is removed already. */
    void remove(long ruleId) {
        ignoreRuleRepository.delete(ruleId);
        log.info("Ignore rule {} is removed", ruleId);
    }

    private RuleSource ruleSource(Long articleId) {
        if (articleId == null) {
            throw new FieldValidationException("articleId", "An article is required.");
        }
        return ignoreRuleRepository.findRuleSource(articleId).orElseThrow(ArticleNotFoundException::new);
    }

    private StoredRule ignoreAuthor(RuleSource ruleSource) {
        if (ruleSource.authorKey() == null) {
            throw new FieldValidationException("articleId", "This article has no known author.");
        }
        return ignoreRuleRepository.store(IgnoreFilters.AUTHOR, ruleSource.authorKey(),
                AuthorName.of(ruleSource.from()), null, System.currentTimeMillis());
    }

    private StoredRule ignoreThread(RuleSource ruleSource) {
        // The root can have changed since an earlier rule was created; the thread is ignored all the same.
        Optional<StoredRule> ruleOfThread = ignoreRuleRepository.findThreadRule(ruleSource.threadRootId());
        if (ruleOfThread.isPresent()) {
            return ruleOfThread.get();
        }
        // A root that is a stub has no subject; the article the owner chose stands in.
        String label = firstText(ruleSource.rootSubject(), ruleSource.subject());
        return ignoreRuleRepository.store(IgnoreFilters.THREAD, ruleSource.rootMessageId(), label, null,
                System.currentTimeMillis());
    }

    /** A subscribed group cannot be ignored, so ignoring never hides a group the owner reads (spec 022). */
    private StoredRule ignoreGroup(Long groupId) {
        if (groupId == null) {
            throw new FieldValidationException("groupId", "A group is required.");
        }
        RuleGroup group = ignoreRuleRepository.findGroup(groupId).orElseThrow(GroupNotFoundException::new);
        if (group.subscribed()) {
            throw new FieldValidationException("groupId", "A subscribed group cannot be ignored.");
        }
        return ignoreRuleRepository.store(IgnoreFilters.GROUP, group.name(), group.name(), null,
                System.currentTimeMillis());
    }

    /** A word or phrase in subjects, for all groups or for one subscribed group (spec 023). */
    private StoredRule ignoreSubject(String text, Long groupId) {
        String label = text == null ? "" : text.strip();
        String word = SubjectWords.ofRule(label);
        if (label.isEmpty() || label.length() > SUBJECT_TEXT_MAX_LENGTH) {
            throw new FieldValidationException("text",
                    "The word must have 1 to " + SUBJECT_TEXT_MAX_LENGTH + " characters.");
        }
        if (word.isEmpty()) {
            throw new FieldValidationException("text", "The word must have at least one letter or digit.");
        }
        if (groupId != null) {
            RuleGroup group = ignoreRuleRepository.findGroup(groupId).orElseThrow(GroupNotFoundException::new);
            if (!group.subscribed()) {
                throw new FieldValidationException("groupId", "The group is not subscribed.");
            }
        }
        return ignoreRuleRepository.store(IgnoreFilters.SUBJECT, word, label, groupId, System.currentTimeMillis());
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
