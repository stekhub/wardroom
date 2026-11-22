package io.github.stekhub.wardroom.posting.internal;

import io.github.stekhub.wardroom.posting.internal.PostingIdentityService.Identity;
import io.github.stekhub.wardroom.posting.internal.PostingService.Draft;
import io.github.stekhub.wardroom.posting.internal.PostingService.Posted;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints for the posting identity and for posting (spec 009). */
@Tag(name = "Posting")
@RestController
@RequiredArgsConstructor
class PostingController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final PostingIdentityService postingIdentityService;
    private final PostingService postingService;

    @Schema(name = "PostingIdentity", description = "The name, email address and signature the owner posts with "
            + "on a server. All fields are null until an identity is set")
    record IdentityBody(
            @Schema(nullable = true) String name,
            @Schema(nullable = true) String email,
            @Schema(description = "Appended to a post after a line \"-- \"; null when there is none",
                    nullable = true)
            String signature) {

        static IdentityBody of(Identity identity) {
            return new IdentityBody(identity.name(), identity.email(), identity.signature());
        }
    }

    @Schema(name = "ArticleDraft", description = "What the owner wrote. The article is built from it by the "
            + "backend")
    record DraftBody(
            @Schema(description = "The article that is answered; null for a new discussion", nullable = true)
            Long parentId,
            @Schema(nullable = true) String subject,
            @Schema(description = "The text, with the quoted lines of a reply", nullable = true) String body,
            @Schema(description = "Whether the signature of the identity is appended; true when absent",
                    nullable = true)
            Boolean includeSignature,
            @Schema(description = "Null at the first attempt; afterwards the messageId of the failed attempt, so "
                    + "that trying again cannot create a second article", nullable = true)
            String messageId) {
    }

    @Schema(name = "PostedArticle")
    record PostedBody(
            @NotNull long articleId,
            @Schema(description = "The root of the thread the article is in")
            @NotNull long rootId,
            @NotNull String messageId) {
    }

    @Operation(summary = "Returns the posting identity of a server", operationId = "getPostingIdentity")
    @ApiResponse(responseCode = "200", description = "The identity; its fields are null when none is set")
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/api/servers/{serverId}/identity", produces = MediaType.APPLICATION_JSON_VALUE)
    IdentityBody identity(@PathVariable long serverId) {
        return IdentityBody.of(postingIdentityService.identity(serverId));
    }

    @Operation(summary = "Stores the posting identity of a server", operationId = "storePostingIdentity")
    @ApiResponse(responseCode = "200", description = "The identity as it is stored")
    @ApiResponse(responseCode = "400", description = "The name is missing or the email address is invalid",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(path = "/api/servers/{serverId}/identity", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    IdentityBody storeIdentity(@PathVariable long serverId, @RequestBody IdentityBody identity) {
        return IdentityBody.of(postingIdentityService.store(serverId, identity.name(), identity.email(),
                identity.signature()));
    }

    @Operation(summary = "Posts a new discussion or a reply from a subscribed group", operationId = "postArticle")
    @ApiResponse(responseCode = "201", description = "The server accepted the article; it is stored")
    @ApiResponse(responseCode = "400", description = "The subject or the text is missing, the article is larger "
            + "than 64 KB, or the messageId is not one of Wardroom", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No group or parent has this ID, or the group is not "
            + "subscribed", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "No posting identity is set, the group does not allow "
            + "posting, the author of the parent asked for replies by email, or the parent has no content",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The server did not take the article. The type is "
            + "POSTING_NOT_ALLOWED, AUTH_REQUIRED, POSTING_FAILED or UNREACHABLE, the detail is the message of "
            + "the server, and messageId is what to try again with", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/groups/{groupId}/articles", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PostedBody post(@PathVariable long groupId, @RequestBody DraftBody draft) {
        Posted posted = postingService.post(groupId, new Draft(draft.parentId(), draft.subject(), draft.body(),
                !Boolean.FALSE.equals(draft.includeSignature()), draft.messageId()));
        return new PostedBody(posted.articleId(), posted.rootId(), posted.messageId());
    }
}
