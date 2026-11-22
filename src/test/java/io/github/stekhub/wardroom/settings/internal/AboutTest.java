package io.github.stekhub.wardroom.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The version for the settings section "About". */
@IntegrationTest
class AboutTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    BuildProperties buildProperties;

    @Test
    void theOwnerGetsTheVersionOfTheBuild() {
        TestBrowser browser = TestInstallation.loggedInOwner(mvc, database);

        assertThat(browser.get("/api/about")).hasStatusOk().bodyJson()
                .isStrictlyEqualTo("{\"version\": \"" + buildProperties.getVersion() + "\"}");
        assertThat(buildProperties.getVersion()).as("the version of the Maven build").matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void theVersionNeedsASession() {
        TestInstallation.loggedInOwner(mvc, database);

        assertThat(new TestBrowser(mvc).get("/api/about")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
