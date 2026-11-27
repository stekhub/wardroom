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

/** The endpoints for ignoring authors, threads, groups and words in subjects (spec 010, 022, 023). */
@Tag(name = "Ignoring")
@RestController
@RequiredArgsConstructor
class IgnoreRuleController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final IgnoreRuleService ignoreRuleService;

    @Schema(name = "IgnoreRuleRequest")
    record IgnoreRuleRequest(
            @Schema(description = "What is ignored: the author of the article, the thread of the article, a group "
                    + "in the group list, or a word or phrase in subjects")
            @NotNull Kind kind,
            @Schema(description = "For AUTHOR and THREAD: an article by the author, or any article or stub of the "
                    + "thread")
            Long articleId,
            @Schema(description = "For GROUP: the group, which must not be subscribed. For SUBJECT: the subscribed "
                    + "group the rule is for; without it the rule is for all groups")
            Long groupId,
            @Schema(description = "For SUBJECT: the word or phrase, 1 to 100 characters with at least one letter "
                    + "or digit")
            String text) {
    }

    @Schema(name = "IgnoreRule", description = "An author, a thread, a group or a word in subjects the owner "
            + "ignores")
    record IgnoreRuleBody(
            @NotNull long id,
            @NotNull Kind kind,
            @Schema(description = "The display name of the author, the subject of the thread, the group name, or "
                    + "the word as it was typed; untrusted text")
            @NotNull String label,
            @Schema(description = "When the rule was created, in UTC milliseconds")
            @NotNull long createdAt,
            @Schema(description = "The group of a SUBJECT rule of one group; otherwise null")
            Long groupId,
            @Schema(description = "The name of that group; otherwise null")
            String groupName) {

        static IgnoreRuleBody of(StoredRule rule) {
            return new IgnoreRuleBody(rule.id(), Kind.valueOf(rule.kind()), rule.label(), rule.createdAt(),
                    rule.groupId(), rule.groupName());
        }
    }

    @Operation(summary = "Lists the ignore rules, the newest first", operationId = "listIgnoreRules")
    @ApiResponse(responseCode = "200", description = "The rules")
    @GetMapping(path = "/api/ignore-rules", produces = MediaType.APPLICATION_JSON_VALUE)
    List<IgnoreRuleBody> rules() {
        return ignoreRuleService.rules().stream().map(IgnoreRuleBody::of).toList();
    }

    @Operation(summary = "Ignores the author or the thread of an article, a group, or a word in subjects",
            operationId = "createIgnoreRule")
    @ApiResponse(responseCode = "201", description = "The rule; when the same is ignored already, the rule that "
            + "does so, unchanged")
    @ApiResponse(responseCode = "400", description = "The request is invalid, the article has no known author, the "
            + "group to ignore is subscribed, the group of a word is not subscribed, or the word breaks a rule",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article or no group has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/ignore-rules", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    IgnoreRuleBody create(@Valid @RequestBody IgnoreRuleRequest request) {
        return IgnoreRuleBody.of(ignoreRuleService.ignore(request.kind(), request.articleId(), request.groupId(),
                request.text()));
    }

    @Operation(summary = "Removes an ignore rule", operationId = "deleteIgnoreRule")
    @ApiResponse(responseCode = "204", description = "The rule is removed, or was removed already")
    @DeleteMapping("/api/ignore-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long ruleId) {
        ignoreRuleService.remove(ruleId);
    }
}
