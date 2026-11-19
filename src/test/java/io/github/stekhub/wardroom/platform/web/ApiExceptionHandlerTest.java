package io.github.stekhub.wardroom.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@Import(ApiExceptionHandlerTest.FailingController.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private static final String INTERNAL_DETAIL = "internal detail that must stay on the server";

    @Autowired
    MockMvcTester mvc;

    @Test
    void unexpectedErrorIsAnsweredAsProblemDetailsWithoutInternals(CapturedOutput output) {
        assertThat(mvc.get().uri("/api/test/failure"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo(500);
                    assertThat(json).extractingPath("$.title").isEqualTo("Internal Server Error");
                })
                .asString()
                .doesNotContain(INTERNAL_DETAIL);

        assertThat(output.getOut())
                .as("ADR 0029: a failed request is logged with method, path and stack trace")
                .contains("ERROR")
                .contains("Unexpected error in GET /api/test/failure")
                .contains("java.lang.IllegalStateException: " + INTERNAL_DETAIL);
    }

    @Test
    void unknownApiPathIsAnsweredAsProblemDetails() {
        assertThat(mvc.get().uri("/api/does-not-exist"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo(404);
    }

    @TestComponent
    @RestController
    static class FailingController {

        @GetMapping("/api/test/failure")
        String fail() {
            throw new IllegalStateException(INTERNAL_DETAIL);
        }
    }
}
