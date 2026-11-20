package io.github.stekhub.wardroom.sync.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The synchronization endpoints of spec 005. How a synchronization ended is part of the subscription list. */
@Tag(name = "Synchronization")
@RestController
@RequiredArgsConstructor
class SynchronizationController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final SynchronizationService synchronizationService;

    @Operation(summary = "Starts the synchronization of the group", operationId = "synchronizeGroup")
    @ApiResponse(responseCode = "202", description = "A synchronization was started or is running already")
    @ApiResponse(responseCode = "404", description = "No group has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The group is not subscribed, or the credentials of the "
            + "server are invalid", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/api/groups/{groupId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void synchronize(@PathVariable long groupId) {
        synchronizationService.requestSynchronization(groupId);
    }
}
