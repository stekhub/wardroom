package io.github.stekhub.wardroom.settings.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the settings section "About" shows and the frontend cannot know: the version of Wardroom (UI concept). */
@Tag(name = "About")
@RestController
@RequiredArgsConstructor
class AboutController {

    private final ObjectProvider<BuildProperties> buildProperties;

    @Operation(summary = "Returns the version of Wardroom", operationId = "getAbout")
    @ApiResponse(responseCode = "200", description = "The version")
    @GetMapping(path = "/api/about", produces = MediaType.APPLICATION_JSON_VALUE)
    AboutBody about() {
        BuildProperties build = buildProperties.getIfAvailable();
        return new AboutBody(build == null ? null : build.getVersion());
    }

    @Schema(name = "About")
    record AboutBody(
            @Schema(description = "The version of Wardroom, for example 0.9.0; null in a development build without "
                    + "version information") String version) {
    }
}
