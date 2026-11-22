package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.internal.ThreadListService.Card;
import io.github.stekhub.wardroom.articles.internal.ThreadListService.Entry;
import io.github.stekhub.wardroom.articles.internal.ThreadListService.ThreadPage;
import io.github.stekhub.wardroom.articles.internal.ThreadListService.ThreadView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints of the discussion view (spec 008). */
@Tag(name = "Threads")
@RestController
@RequiredArgsConstructor
class ThreadController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final ThreadListService threadListService;

    @Schema(name = "ThreadCard", description = "A thread of a group as its card shows it. All text in it is "
            + "untrusted")
    record ThreadCardBody(
            @Schema(description = "The topmost article of the thread, which can be a stub")
            @NotNull long rootId,
            @Schema(description = "The article whose subject, author and date stand for the thread")
            @NotNull long cardArticleId,
            @Schema(nullable = true) String subject,
            @Schema(nullable = true) String from,
            @Schema(description = "The key the author icon is derived from", nullable = true)
            String authorKey,
            @Schema(description = "The date of the card article in UTC milliseconds; null when it cannot be read",
                    nullable = true)
            Long date,
            @Schema(description = "The beginning of the text of the card article; null when its content is not "
                    + "cached or it has no text of its own", nullable = true)
            String excerpt,
            @Schema(description = "The articles of the thread located in the group")
            @NotNull int messageCount,
            @Schema(description = "The newest date among those articles in UTC milliseconds; null when none has "
                    + "a date", nullable = true)
            Long latestActivity,
            @Schema(description = "How many of those articles are not read")
            @NotNull int unreadCount) {

        static ThreadCardBody of(Card card) {
            return new ThreadCardBody(card.rootId(), card.cardArticleId(), card.subject(), card.from(),
                    card.authorKey(), card.date(), card.excerpt(), card.messageCount(), card.latestActivity(),
                    card.unreadCount());
        }
    }

    @Schema(name = "ThreadPage", description = "One page of the thread cards of a group")
    record ThreadPageBody(
            @NotNull List<ThreadCardBody> items,
            @Schema(description = "To be sent as cursor for the next page; null on the last page", nullable = true)
            String nextCursor,
            @Schema(description = "The synchronized range the list shows: the newest one of the group, or the "
                    + "one of the jump in a date view; null when the group has none yet", nullable = true)
            Long rangeId,
            @Schema(description = "Whether nothing older than the range can be loaded")
            @NotNull boolean lowWatermarkReached,
            @Schema(description = "Whether nothing newer than the range can be loaded")
            @NotNull boolean highWatermarkReached) {

        static ThreadPageBody of(ThreadPage threadPage) {
            return new ThreadPageBody(threadPage.items().stream().map(ThreadCardBody::of).toList(),
                    threadPage.nextCursor(), threadPage.rangeId(), threadPage.lowWatermarkReached(),
                    threadPage.highWatermarkReached());
        }
    }

    @Schema(name = "ThreadArticle", description = "An article or stub of a thread. All text in it is untrusted")
    record ThreadArticleBody(
            @NotNull long id,
            @Schema(description = "The article it answers; null for the thread root", nullable = true)
            Long parentId,
            @Schema(description = "How many articles lie between it and the thread root; 0 for the root")
            @NotNull int depth,
            @Schema(description = "Whether the article is only referenced and not stored")
            @NotNull boolean isStub,
            @Schema(nullable = true) String subject,
            @Schema(nullable = true) String from,
            @Schema(description = "The key the author icon is derived from; null for a stub", nullable = true)
            String authorKey,
            @Schema(description = "Whether the owner ignores the author of the article")
            @NotNull boolean ignoredAuthor,
            @Schema(description = "UTC milliseconds; null when the date cannot be read", nullable = true)
            Long date,
            @NotNull boolean read,
            @Schema(description = "The name of a group the article is located in, when it is not located in this "
                    + "group", nullable = true)
            String otherGroup,
            @Schema(description = "Whether the owner posted the article from this group and the server does not "
                    + "list it there yet")
            @NotNull boolean sent) {

        static ThreadArticleBody of(Entry entry) {
            return new ThreadArticleBody(entry.id(), entry.parentId(), entry.depth(), entry.stub(),
                    entry.subject(), entry.from(), entry.authorKey(), entry.ignoredAuthor(), entry.date(),
                    entry.read(), entry.otherGroup(), entry.sent());
        }
    }

    @Schema(name = "Thread", description = "A thread with its articles in display order")
    record ThreadBody(
            @Schema(description = "The current root of the thread")
            @NotNull long rootId,
            @Schema(description = "Whether the owner ignores the thread")
            @NotNull boolean ignored,
            @Schema(description = "The ignore rules that ignore the thread; removing them all stops ignoring it. "
                    + "Empty when the thread is not ignored")
            @NotNull List<Long> ignoreRuleIds,
            @NotNull List<ThreadArticleBody> articles) {

        static ThreadBody of(ThreadView threadView) {
            return new ThreadBody(threadView.rootId(), !threadView.ignoreRuleIds().isEmpty(),
                    threadView.ignoreRuleIds(),
                    threadView.articles().stream().map(ThreadArticleBody::of).toList());
        }
    }

    @Operation(summary = "Lists the threads of a subscribed group as cards, one page at a time",
            operationId = "listThreads")
    @ApiResponse(responseCode = "200", description = "A page of thread cards")
    @ApiResponse(responseCode = "400", description = "A parameter is invalid", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No group has this ID, or the group is not subscribed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/api/groups/{groupId}/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    ThreadPageBody threads(@PathVariable long groupId,
            @Parameter(description = "newest, activity (the default) or discussed; not together with until")
            @RequestParam(required = false) String sort,
            @Parameter(description = "An ISO 8601 instant: only threads that began before this time, newest "
                    + "first")
            @RequestParam(required = false) String until,
            @Parameter(description = "The range of the jump; required with until")
            @RequestParam(required = false) Long rangeId,
            @Parameter(description = "The nextCursor of the page before")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "How many cards; 50 by default, 100 at most")
            @RequestParam(required = false) Integer limit) {
        return ThreadPageBody.of(threadListService.threads(groupId, sort, until, rangeId, cursor, limit));
    }

    @Operation(summary = "Returns the thread that an article belongs to", operationId = "getThread")
    @ApiResponse(responseCode = "200", description = "The thread")
    @ApiResponse(responseCode = "404", description = "No group or article has this ID, the group is not "
            + "subscribed, or the thread has no article in the group", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/api/groups/{groupId}/threads/{articleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ThreadBody thread(@PathVariable long groupId, @PathVariable long articleId) {
        return ThreadBody.of(threadListService.thread(groupId, articleId));
    }

    @Operation(summary = "Marks the articles of a thread that are located in the group as read",
            operationId = "markThreadRead")
    @ApiResponse(responseCode = "204", description = "The articles of the thread in the group are read")
    @ApiResponse(responseCode = "404", description = "No group or article has this ID, or the thread has no "
            + "article in the group", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The group is not subscribed", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/api/groups/{groupId}/threads/{articleId}/mark-read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markThreadRead(@PathVariable long groupId, @PathVariable long articleId) {
        threadListService.markThreadRead(groupId, articleId);
    }
}
