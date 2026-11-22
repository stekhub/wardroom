package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.sync.internal.RangeLoader.Jump;
import io.github.stekhub.wardroom.sync.internal.RangeLoader.LoadedRange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints for jump to date and "load older" (spec 005). Both run during the request and return
 * when the overviews are stored.
 */
@Tag(name = "Synchronization")
@RestController
@RequiredArgsConstructor
class RangeLoadController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final RangeLoadService rangeLoadService;

    @Schema(name = "JumpRequest")
    record JumpRequest(
            @Schema(description = "The time before which the group is shown, in UTC milliseconds: the end of the "
                    + "chosen day in the time zone of the browser, which is the start of the next day")
            @NotNull Long until) {
    }

    @Schema(name = "Range", description = "A range of article numbers whose overviews are stored")
    record RangeBody(
            @NotNull long rangeId,
            @NotNull long firstNumber,
            @NotNull long lastNumber,
            @Schema(description = "Whether nothing older can be loaded")
            @NotNull boolean atOldest,
            @Schema(description = "Whether the range is the newest of the group")
            @NotNull boolean atNewest) {

        static RangeBody of(LoadedRange range) {
            return new RangeBody(range.rangeId(), range.firstNumber(), range.lastNumber(), range.atOldest(),
                    range.atNewest());
        }
    }

    @Schema(name = "JumpResult")
    record JumpResultBody(
            @Schema(description = "The range that ends the view; null when the chosen day is before the oldest "
                    + "article of the server", nullable = true)
            RangeBody range,
            @Schema(description = "The date of the oldest article of the server, in UTC milliseconds, when there "
                    + "is no range; otherwise null", nullable = true)
            Long oldestDate) {
    }

    @Operation(summary = "Finds the last article before a time and loads the overviews that end there",
            operationId = "jumpToDate")
    @ApiResponse(responseCode = "200", description = "The range that ends the view")
    @ApiResponse(responseCode = "400", description = "The request has no time", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No group has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The group is not subscribed, not synchronized yet or being "
            + "synchronized, or the credentials of the server are invalid", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The server could not be asked, or the dates of the group "
            + "could not be read", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/groups/{groupId}/jump", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    JumpResultBody jump(@PathVariable long groupId, @Valid @RequestBody JumpRequest request) {
        Jump jump = rangeLoadService.jump(groupId, request.until());
        return new JumpResultBody(jump.range() == null ? null : RangeBody.of(jump.range()), jump.oldestDate());
    }

    @Operation(summary = "Loads older overviews below a range", operationId = "extendRange")
    @ApiResponse(responseCode = "200", description = "The range as it is stored now")
    @ApiResponse(responseCode = "404", description = "No group has this ID, or the group has no such range",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The group is not subscribed or being synchronized, or the "
            + "credentials of the server are invalid", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The server could not be asked", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/groups/{groupId}/ranges/{rangeId}/extend",
            produces = MediaType.APPLICATION_JSON_VALUE)
    RangeBody extend(@PathVariable long groupId, @PathVariable long rangeId) {
        return RangeBody.of(rangeLoadService.extend(groupId, rangeId));
    }
}
