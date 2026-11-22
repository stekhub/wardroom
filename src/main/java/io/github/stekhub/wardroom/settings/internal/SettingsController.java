package io.github.stekhub.wardroom.settings.internal;

import io.github.stekhub.wardroom.settings.CacheUse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The settings endpoints of spec 005. */
@Tag(name = "Settings")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
class SettingsController {

    private final SettingsService settingsService;
    private final CacheUse cacheUse;

    @Operation(summary = "Returns the instance settings", operationId = "getSettings")
    @ApiResponse(responseCode = "200", description = "The settings")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    SettingsBody settings() {
        return currentSettings();
    }

    @Operation(summary = "Saves the instance settings", operationId = "saveSettings")
    @ApiResponse(responseCode = "200", description = "The settings as they are stored now")
    @ApiResponse(responseCode = "400", description = "A value is outside its range", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    SettingsBody save(@Valid @RequestBody SettingsBody request) {
        settingsService.save(request);
        // A lower limit, or a limit that was switched on, is applied at once (spec 005).
        cacheUse.settingsChanged();
        return currentSettings();
    }

    private SettingsBody currentSettings() {
        return SettingsBody.of(settingsService.synchronization(), settingsService.cache(), cacheUse.usedBytes(),
                cacheUse.limitUnreachable());
    }
}
