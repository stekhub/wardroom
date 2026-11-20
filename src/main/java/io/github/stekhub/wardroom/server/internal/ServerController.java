package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionTestRequest;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionTestResult;
import io.github.stekhub.wardroom.server.internal.ServerRequests.SavedServer;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerRequest;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The server endpoints of spec 002. The path is plural, so several servers would need no API change. */
@Tag(name = "Servers")
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
class ServerController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final ServerService serverService;

    @Operation(summary = "Lists the servers: none or one", operationId = "listServers")
    @ApiResponse(responseCode = "200", description = "The configured servers")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ServerResponse> servers() {
        return serverService.servers().stream().map(ServerResponse::of).toList();
    }

    @Operation(summary = "Adds the server and tests the connection", operationId = "createServer")
    @ApiResponse(responseCode = "201", description = "Saved, also when the connection test failed")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A server already exists", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SavedServer create(@Valid @RequestBody ServerRequest serverRequest) {
        return serverService.create(serverRequest);
    }

    @Operation(summary = "Changes the server and tests the connection", operationId = "updateServer")
    @ApiResponse(responseCode = "200", description = "Saved, also when the connection test failed")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(path = "/{serverId}", produces = MediaType.APPLICATION_JSON_VALUE)
    SavedServer update(@PathVariable long serverId, @Valid @RequestBody ServerRequest serverRequest) {
        return serverService.update(serverId, serverRequest);
    }

    @Operation(summary = "Removes the server and everything that belongs to it", operationId = "deleteServer")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "404", description = "No server has this ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable long serverId) {
        serverService.remove(serverId);
    }

    @Operation(summary = "Tests a connection with the given values without saving them",
            operationId = "testServerConnection")
    @ApiResponse(responseCode = "200", description = "The result; a failed test is a result, not an error")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No server has the given ID", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/connection-test", produces = MediaType.APPLICATION_JSON_VALUE)
    ConnectionTestResult testConnection(@Valid @RequestBody ConnectionTestRequest connectionTestRequest) {
        return serverService.test(connectionTestRequest);
    }
}
