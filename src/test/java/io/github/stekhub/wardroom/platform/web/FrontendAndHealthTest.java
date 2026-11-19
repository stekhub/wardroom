package io.github.stekhub.wardroom.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.platform.web.FrontendConfiguration.FrontendResourceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class FrontendAndHealthTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthEndpointReportsUpWithoutDetails() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("{\"status\": \"UP\"}")
                .asString()
                .doesNotContain("components");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/actuator/info")).hasStatus(HttpStatus.NOT_FOUND);
    }

    /** These two tests need the built frontend, which the Maven build provides before the tests run. */
    @Test
    void rootServesTheFrontend() {
        assertThat(mvc.get().uri("/index.html"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                .bodyText()
                .contains("<div id=\"root\">");
    }

    @Test
    void frontendRouteServesTheFrontend() {
        assertThat(mvc.get().uri("/settings/server"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                .bodyText()
                .contains("<div id=\"root\">");
    }

    @Test
    void missingFileIsNotAnsweredWithTheFrontend() {
        assertThat(mvc.get().uri("/assets/missing.js")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void frontendRoutesAreToldApartFromBackendPathsAndFiles() {
        assertThat(FrontendResourceResolver.isFrontendRoute("settings/server")).isTrue();
        assertThat(FrontendResourceResolver.isFrontendRoute("groups/12/threads/345")).isTrue();
        assertThat(FrontendResourceResolver.isFrontendRoute("apiary")).isTrue();
        assertThat(FrontendResourceResolver.isFrontendRoute("api")).isFalse();
        assertThat(FrontendResourceResolver.isFrontendRoute("api/servers")).isFalse();
        assertThat(FrontendResourceResolver.isFrontendRoute("actuator/env")).isFalse();
        assertThat(FrontendResourceResolver.isFrontendRoute("v3/api-docs")).isFalse();
        assertThat(FrontendResourceResolver.isFrontendRoute("assets/missing.js")).isFalse();
    }
}
