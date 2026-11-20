package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.SubscriptionResponses.SubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The subscription endpoints of spec 004. */
@Tag(name = "Subscriptions")
@RestController
@RequiredArgsConstructor
class SubscriptionController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final SubscriptionService subscriptionService;

    @Operation(summary = "Lists the subscribed groups by name, with their unread counts",
            operationId = "listSubscriptions")
    @ApiResponse(responseCode = "200", description = "The subscribed groups")
    @GetMapping(path = "/api/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    List<SubscriptionResponse> subscriptions() {
        return subscriptionService.subscriptions();
    }

    @Operation(summary = "Subscribes to the group", operationId = "subscribe")
    @ApiResponse(responseCode = "204", description = "The group is subscribed; it may have been so before")
    @ApiResponse(responseCode = "404", description = "No group has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The server no longer lists the group", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/api/groups/{groupId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void subscribe(@PathVariable long groupId) {
        subscriptionService.subscribe(groupId);
    }

    @Operation(summary = "Unsubscribes from the group and deletes its cached articles and their read state",
            operationId = "unsubscribe")
    @ApiResponse(responseCode = "204", description = "The group is not subscribed; it may not have been before")
    @ApiResponse(responseCode = "404", description = "No group has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/api/groups/{groupId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@PathVariable long groupId) {
        subscriptionService.unsubscribe(groupId);
    }

    @Operation(summary = "Marks all articles of the group as read", operationId = "markAllRead")
    @ApiResponse(responseCode = "204", description = "All articles of the group are read")
    @ApiResponse(responseCode = "404", description = "No group has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The group is not subscribed", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/api/groups/{groupId}/mark-all-read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAllRead(@PathVariable long groupId) {
        subscriptionService.markAllRead(groupId);
    }
}
