package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.internal.ArticleContentService.ArticleContent;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints for the content of an article and for marking it as read (spec 007). */
@Tag(name = "Articles")
@RestController
@RequiredArgsConstructor
class ArticleContentController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final ArticleContentService articleContentService;

    @Schema(name = "OmittedPart", description = "A part of an article that is not displayed and not stored")
    record OmittedPartBody(
            @Schema(description = "The file name the article gives; untrusted text", nullable = true)
            String name,
            @NotNull String mediaType,
            @Schema(description = "The size in bytes")
            @NotNull long size) {
    }

    @Schema(name = "ArticleContent", description = "The content of an article. All text in it is untrusted")
    record ArticleContentBody(
            @NotNull long articleId,
            @Schema(description = "The plain text with line feeds as line endings; empty when the article has none")
            @NotNull String body,
            @Schema(description = "Whether the text is format=flowed (RFC 3676) and has to be unwrapped")
            @NotNull boolean flowed,
            @Schema(description = "Whether the flowed text was sent with DelSp=yes")
            @NotNull boolean delSp,
            @Schema(description = "Whether a limit stopped the processing, so that the text is not all of the "
                    + "article")
            @NotNull boolean truncated,
            @NotNull List<OmittedPartBody> omittedParts,
            @Schema(description = "The header block as received")
            @NotNull String rawHeaders) {

        static ArticleContentBody of(ArticleContent articleContent) {
            return new ArticleContentBody(articleContent.articleId(), articleContent.body(),
                    articleContent.flowed(), articleContent.delSp(), articleContent.truncated(),
                    articleContent.omittedParts().stream()
                            .map(part -> new OmittedPartBody(part.name(), part.mediaType(), part.size()))
                            .toList(),
                    articleContent.rawHeaders());
        }
    }

    @Operation(summary = "Returns the content of an article, fetching it from the server when it is not cached",
            operationId = "getArticleContent")
    @ApiResponse(responseCode = "200", description = "The content")
    @ApiResponse(responseCode = "404", description = "No article has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410", description = "The server no longer has the article", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The content could not be fetched", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/api/articles/{articleId}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    ArticleContentBody content(@PathVariable long articleId) {
        return ArticleContentBody.of(articleContentService.content(articleId));
    }

    @Operation(summary = "Asks the server again for an article it did not have at an earlier attempt",
            operationId = "retryArticleContent")
    @ApiResponse(responseCode = "200", description = "The content")
    @ApiResponse(responseCode = "404", description = "No article has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410", description = "The server still does not have the article",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The content could not be fetched", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/articles/{articleId}/content/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    ArticleContentBody retry(@PathVariable long articleId) {
        return ArticleContentBody.of(articleContentService.retry(articleId));
    }

    @Operation(summary = "Marks an article as read", operationId = "markArticleRead")
    @ApiResponse(responseCode = "204", description = "The article is read")
    @ApiResponse(responseCode = "404", description = "No article has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/api/articles/{articleId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@PathVariable long articleId) {
        articleContentService.markRead(articleId);
    }
}
