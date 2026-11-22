package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.internal.ArticleFailures.GroupNotFoundException;
import io.github.stekhub.wardroom.articles.internal.ArticleFailures.GroupNotSubscribedException;
import io.github.stekhub.wardroom.articles.internal.ArticleFailures.ThreadNotFoundException;
import io.github.stekhub.wardroom.articles.internal.ThreadListRepository.ListedRange;
import io.github.stekhub.wardroom.articles.internal.ThreadListRepository.ThreadArticle;
import io.github.stekhub.wardroom.articles.internal.ThreadListRepository.ThreadCard;
import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The thread list of a group, the thread page and "mark thread as read" (spec 008). */
@Service
@RequiredArgsConstructor
class ThreadListService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    /** Replies to the same parent: by date, oldest first; without a date, by article number in the group. */
    private static final Comparator<ThreadArticle> AMONG_REPLIES = Comparator
            .comparing(ThreadArticle::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ThreadArticle::numberInGroup, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ThreadArticle::articleId);

    private final ThreadListRepository threadListRepository;
    private final ThreadRepository threadRepository;
    private final SubscribedGroups subscribedGroups;

    /**
     * One page of thread cards.
     *
     * @param nextCursor            where the next page begins, or {@code null} when this is the last one
     * @param rangeId               the synchronized range the list shows, or {@code null} when the group has none
     * @param lowWatermarkReached   whether nothing older than the range can be loaded
     * @param highWatermarkReached  whether nothing newer than the range can be loaded
     */
    record ThreadPage(List<Card> items, String nextCursor, Long rangeId, boolean lowWatermarkReached,
                      boolean highWatermarkReached) {
    }

    /** A thread card. All text in it is untrusted. */
    record Card(long rootId, long cardArticleId, String subject, String from, String authorKey, Long date,
                String excerpt, int messageCount, Long latestActivity, int unreadCount) {
    }

    /**
     * A thread as its page shows it: the articles in display order.
     *
     * @param ignoreRuleIds the rules that ignore the thread; empty when it is not ignored (spec 010)
     */
    record ThreadView(long rootId, List<Long> ignoreRuleIds, List<Entry> articles) {
    }

    /**
     * An article or stub of a thread.
     *
     * @param depth         how many articles lie between it and the thread root, the root being 0
     * @param ignoredAuthor whether the owner ignores the author (spec 010)
     * @param otherGroup    the group it was posted to, when it is not located in the group of the page
     */
    record Entry(long id, Long parentId, int depth, boolean stub, String subject, String from, String authorKey,
                 boolean ignoredAuthor, Long date, boolean read, String otherGroup) {
    }

    /**
     * @param sort    {@code newest}, {@code activity} or {@code discussed}; {@code null} for the default
     * @param until   an ISO 8601 instant for a date view, or {@code null}
     * @param rangeId the range of a date view; required with {@code until}
     * @param cursor  the cursor of the page before, or {@code null}
     * @param limit   how many cards, or {@code null} for the default
     * @throws GroupNotFoundException   when the group is unknown or not subscribed
     * @throws FieldValidationException when a parameter is invalid
     */
    ThreadPage threads(long groupId, String sort, String until, Long rangeId, String cursor, Integer limit) {
        if (subscribedGroups.find(groupId).isEmpty()) {
            throw new GroupNotFoundException();
        }
        int cardLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (cardLimit < 1 || cardLimit > MAX_LIMIT) {
            throw new FieldValidationException("limit", "The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        if (until != null && sort != null) {
            throw new FieldValidationException("sort", "A list up to a date has no sort order.");
        }
        ThreadOrder order = until != null ? ThreadOrder.UNTIL_DATE : orderOf(sort);
        Long untilMillis = until == null ? null : millisOf(until);
        ListedRange range;
        if (until != null) {
            if (rangeId == null) {
                throw new FieldValidationException("rangeId", "A list up to a date needs the range of the jump.");
            }
            range = threadListRepository.findRange(groupId, rangeId).orElseThrow(
                    () -> new FieldValidationException("rangeId", "The group has no such range."));
        } else {
            if (rangeId != null) {
                throw new FieldValidationException("rangeId", "A range can only be given with a date.");
            }
            range = threadListRepository.findNewestRange(groupId).orElse(null);
        }
        ThreadCursor threadCursor = cursor == null ? null : ThreadCursor.decode(cursor, order).orElseThrow(
                () -> new FieldValidationException("cursor", "This is not a cursor of this list."));

        // One card more than asked for tells whether another page follows.
        List<ThreadCard> threadCards = threadListRepository.findCards(groupId, order, untilMillis,
                until == null ? null : range, threadCursor, cardLimit + 1);
        String nextCursor = null;
        if (threadCards.size() > cardLimit) {
            threadCards = threadCards.subList(0, cardLimit);
            ThreadCard lastCard = threadCards.getLast();
            nextCursor = new ThreadCursor(order, lastCard.firstKey(), lastCard.secondKey(), lastCard.threadRootId())
                    .encode();
        }
        List<Card> cards = threadCards.stream()
                .map(threadCard -> new Card(threadCard.threadRootId(), threadCard.cardArticleId(),
                        threadCard.subject(), threadCard.from(), threadCard.authorKey(), threadCard.date(),
                        Excerpt.of(threadCard.textStart()), threadCard.messageCount(), threadCard.latestActivity(),
                        threadCard.unreadCount()))
                .toList();
        return new ThreadPage(cards, nextCursor, range == null ? null : range.rangeId(),
                range != null && range.atLowWatermark(), range != null && range.newest());
    }

    /**
     * @param articleId any article or stub of the thread
     * @throws GroupNotFoundException  when the group is unknown or not subscribed
     * @throws ThreadNotFoundException when no article has this ID, or its thread has no article in the group
     */
    ThreadView thread(long groupId, long articleId) {
        if (subscribedGroups.find(groupId).isEmpty()) {
            throw new GroupNotFoundException();
        }
        long threadRootId = threadRootInGroup(groupId, articleId);
        return new ThreadView(threadRootId, threadListRepository.findIgnoreRuleIds(threadRootId),
                inDisplayOrder(threadRootId, threadListRepository.findThreadArticles(threadRootId, groupId)));
    }

    /**
     * Marks the unread articles of the thread that are located in the group as read.
     *
     * @throws GroupNotFoundException      when no group has this ID
     * @throws GroupNotSubscribedException when the group is not subscribed
     * @throws ThreadNotFoundException     when no article has this ID, or its thread has no article in the group
     */
    void markThreadRead(long groupId, long articleId) {
        if (!subscribedGroups.exists(groupId)) {
            throw new GroupNotFoundException();
        }
        if (subscribedGroups.find(groupId).isEmpty()) {
            throw new GroupNotSubscribedException();
        }
        threadListRepository.markThreadRead(threadRootInGroup(groupId, articleId), groupId,
                System.currentTimeMillis());
    }

    /** @return the root of the thread of the article, when the group shows this thread */
    private long threadRootInGroup(long groupId, long articleId) {
        long threadRootId = threadRepository.findThreadRootId(articleId).orElseThrow(ThreadNotFoundException::new);
        boolean shownInGroup = threadListRepository.findThreadArticles(threadRootId, groupId).stream()
                .anyMatch(threadArticle -> !threadArticle.stub() && threadArticle.numberInGroup() != null);
        if (!shownInGroup) {
            throw new ThreadNotFoundException();
        }
        return threadRootId;
    }

    /** The root first, then each article followed by its replies (spec 006, "Order within a thread"). */
    private static List<Entry> inDisplayOrder(long threadRootId, List<ThreadArticle> threadArticles) {
        Set<Long> articleIds = new HashSet<>();
        threadArticles.forEach(threadArticle -> articleIds.add(threadArticle.articleId()));
        Map<Long, List<ThreadArticle>> repliesByParentId = new HashMap<>();
        List<ThreadArticle> topArticles = new ArrayList<>();
        for (ThreadArticle threadArticle : threadArticles) {
            Long parentId = threadArticle.parentId();
            if (threadArticle.articleId() == threadRootId || parentId == null || !articleIds.contains(parentId)) {
                topArticles.add(threadArticle);
            } else {
                repliesByParentId.computeIfAbsent(parentId, id -> new ArrayList<>()).add(threadArticle);
            }
        }
        // The thread root comes first; anything else without a parent in the thread follows it.
        topArticles.sort(Comparator.comparing((ThreadArticle threadArticle) ->
                threadArticle.articleId() != threadRootId).thenComparing(AMONG_REPLIES));

        List<Entry> entries = new ArrayList<>(threadArticles.size());
        Set<Long> placedIds = new HashSet<>();
        // A stack and no recursion: a thread can be thousands of replies deep.
        Deque<Placed> waiting = new ArrayDeque<>();
        for (ThreadArticle topArticle : topArticles.reversed()) {
            waiting.push(new Placed(topArticle, 0, null));
        }
        while (!waiting.isEmpty()) {
            Placed placed = waiting.pop();
            ThreadArticle threadArticle = placed.threadArticle();
            // Stored links never form a cycle; an article is still placed only once.
            if (!placedIds.add(threadArticle.articleId())) {
                continue;
            }
            entries.add(new Entry(threadArticle.articleId(), placed.parentId(), placed.depth(),
                    threadArticle.stub(), threadArticle.subject(), threadArticle.from(),
                    threadArticle.authorKey(), threadArticle.ignoredAuthor(), threadArticle.date(),
                    threadArticle.read(), threadArticle.otherGroup()));
            List<ThreadArticle> replies = repliesByParentId.get(threadArticle.articleId());
            if (replies == null) {
                continue;
            }
            replies.sort(AMONG_REPLIES);
            for (ThreadArticle reply : replies.reversed()) {
                waiting.push(new Placed(reply, placed.depth() + 1, threadArticle.articleId()));
            }
        }
        return entries;
    }

    /** An article with its place on the thread page. */
    private record Placed(ThreadArticle threadArticle, int depth, Long parentId) {
    }

    private static ThreadOrder orderOf(String sort) {
        if (sort == null) {
            return ThreadOrder.ACTIVITY;
        }
        return switch (sort) {
            case "newest" -> ThreadOrder.NEWEST;
            case "activity" -> ThreadOrder.ACTIVITY;
            case "discussed" -> ThreadOrder.DISCUSSED;
            default -> throw new FieldValidationException("sort",
                    "The sort order must be newest, activity or discussed.");
        };
    }

    private static long millisOf(String until) {
        try {
            return Instant.parse(until).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new FieldValidationException("until", "The date must be an ISO 8601 instant.");
        }
    }
}
