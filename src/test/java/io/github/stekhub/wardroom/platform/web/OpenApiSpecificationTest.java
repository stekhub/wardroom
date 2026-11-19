package io.github.stekhub.wardroom.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0027: the committed {@code openapi.json} must equal the specification the application produces.
 *
 * <p>After an intended API change, rewrite the file with
 * {@code mvnw test -Dtest=OpenApiSpecificationTest "-Dopenapi.update=true"} and commit it with the change.
 */
@IntegrationTest
class OpenApiSpecificationTest {

    private static final Path COMMITTED = Path.of("openapi.json");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Autowired
    MockMvcTester mvc;

    @Test
    void committedSpecificationMatchesTheApplication() throws IOException {
        String produced = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        JsonNode current = JSON.readTree(produced);

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED, format(current), StandardCharsets.UTF_8);
            return;
        }

        assertThat(COMMITTED).as("openapi.json is committed in the project directory").isRegularFile();
        JsonNode committed = JSON.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8));
        assertThat(committed)
                .as("The API changed. If that is intended, run: "
                        + "mvnw test -Dtest=OpenApiSpecificationTest \"-Dopenapi.update=true\"")
                .isEqualTo(current);
    }

    /** Sorted keys and fixed line endings keep the committed file stable, so a diff shows only real changes. */
    private static String format(JsonNode specification) {
        Object plain = JSON.treeToValue(specification, Object.class);
        return JSON.writeValueAsString(plain).replace("\r\n", "\n") + "\n";
    }
}
