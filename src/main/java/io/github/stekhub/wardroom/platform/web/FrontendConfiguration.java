package io.github.stekhub.wardroom.platform.web;

import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built frontend (ADR 0007). The frontend is a single-page application with its own URLs, such as
 * {@code /settings/server}; the server answers them with {@code index.html} and the frontend shows the right view.
 */
@Configuration(proxyBeanMethods = false)
class FrontendConfiguration implements WebMvcConfigurer {

    /** Paths the backend answers itself. An unknown path below them is a 404, not the frontend. */
    private static final List<String> BACKEND_PREFIXES = List.of("api", "actuator", "v3", "swagger-ui");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new FrontendResourceResolver());
    }

    static class FrontendResourceResolver extends PathResourceResolver {

        private final Resource index = new ClassPathResource("static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null) {
                return resource;
            }
            return isFrontendRoute(resourcePath) && index.isReadable() ? index : null;
        }

        /** A frontend route is not a backend path and does not name a file; a missing file stays a 404. */
        static boolean isFrontendRoute(String path) {
            String firstSegment = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            if (BACKEND_PREFIXES.contains(firstSegment)) {
                return false;
            }
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            return !lastSegment.contains(".");
        }
    }
}
