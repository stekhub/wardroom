package io.github.stekhub.wardroom.ignoring.internal;

import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleRepository.StoredRule;
import io.github.stekhub.wardroom.ignoring.internal.IgnoreRuleService.Kind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints for ignoring authors and threads (spec 010). */
@Tag(name = "Ignoring")
@RestController
@RequiredArgsConstructor
class IgnoreRuleController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final IgnoreRuleService ignoreRuleService;

    @Schema(name = "IgnoreRuleRequest")
    record IgnoreRuleRequest(
            @Schema(description = "What is ignored: the author of the article, or the thread of the article")
            @NotNull Kind kind,
            @Schema(description = "An article by the author, or any article or stub of the thread")
            @NotNull Long articleId) {
    }

    @Schema(name = "IgnoreRule", description = "An author or a thread the owner ignores")
    record IgnoreRuleBody(
            @NotNull long id,
            @NotNull Kind kind,
            @Schema(description = "The display name of the author or the subject of the thread; untrusted text")
            @NotNull String label,
            @Schema(description = "When the rule was created, in UTC milliseconds")
            @NotNull long createdAt) {

        static IgnoreRuleBody of(StoredRule rule) {
            return new IgnoreRuleBody(rule.id(), Kind.valueOf(rule.kind()), rule.label(), rule.createdAt());
        }
    }

    @Operation(summary = "Lists the ignore rules, the newest first", operationId = "listIgnoreRules")
    @ApiResponse(responseCode = "200", description = "The rules")
    @GetMapping(path = "/api/ignore-rules", produces = MediaType.APPLICATION_JSON_VALUE)
    List<IgnoreRuleBody> rules() {
        return ignoreRuleService.rules().stream().map(IgnoreRuleBody::of).toList();
    }

    @Operation(summary = "Ignores the author or the thread of an article", operationId = "createIgnoreRule")
    @ApiResponse(responseCode = "201", description = "The rule; when the author or the thread is ignored already, "
            + "the rule that does so, unchanged")
    @ApiResponse(responseCode = "400", description = "The request is invalid, or the article has no known author",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/ignore-rules", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    IgnoreRuleBody create(@Valid @RequestBody IgnoreRuleRequest request) {
        return IgnoreRuleBody.of(ignoreRuleService.ignore(request.kind(), request.articleId()));
    }

    @Operation(summary = "Removes an ignore rule", operationId = "deleteIgnoreRule")
    @ApiResponse(responseCode = "204", description = "The rule is removed, or was removed already")
    @DeleteMapping("/api/ignore-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long ruleId) {
        ignoreRuleService.remove(ruleId);
    }
}
