package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.GroupResponses.GroupPage;
import io.github.stekhub.wardroom.groups.internal.GroupResponses.RefreshStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The group list endpoints of spec 003. */
@Tag(name = "Groups")
@RestController
@RequestMapping("/api/servers/{serverId}/groups")
@RequiredArgsConstructor
class GroupController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final GroupListService groupListService;
    private final GroupListRefreshService groupListRefreshService;

    @Operation(summary = "Lists the groups of the server, one page at a time", operationId = "listGroups")
    @ApiResponse(responseCode = "200", description = "One page of the groups the server still has")
    @ApiResponse(responseCode = "400", description = "A parameter breaks a rule", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    GroupPage groups(@PathVariable long serverId,
            @Parameter(description = "Search text. A group matches when every word appears in its name or its "
                    + "description; at most 200 characters")
            @RequestParam(name = "q", required = false) String query,
            @Parameter(description = "The order: by name, or the groups with the most articles per day first",
                    schema = @Schema(allowableValues = {"name", "activity"}, defaultValue = "name"))
            @RequestParam(required = false) String sort,
            @Parameter(description = "From 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "1 to 100") @RequestParam(defaultValue = "50") int size) {
        return groupListService.page(serverId, query, sort, page, size);
    }

    @Operation(summary = "Starts a refresh of the group list", operationId = "refreshGroupList")
    @ApiResponse(responseCode = "202", description = "A refresh was started or is running already")
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The credentials of the server are invalid", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void refresh(@PathVariable long serverId) {
        groupListRefreshService.requestRefresh(serverId);
    }

    @Operation(summary = "Says whether a refresh is running and how the last one ended",
            operationId = "getGroupListRefreshStatus")
    @ApiResponse(responseCode = "200", description = "The refresh status")
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    RefreshStatus refreshStatus(@PathVariable long serverId) {
        return groupListRefreshService.status(serverId);
    }
}
