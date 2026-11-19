package io.github.stekhub.wardroom.platform.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fixed parts of the OpenAPI specification. They must not depend on the host or the release, because the
 * specification is committed as {@code openapi.json} and compared in a test (ADR 0027).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI wardroomOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Wardroom API").version("1"))
                .servers(List.of(new Server().url("/")));
    }
}
