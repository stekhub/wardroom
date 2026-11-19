package io.github.stekhub.wardroom.platform.web;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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
        // The build gives every file below /assets a name that contains a hash of its content, so the browser may
        // keep these files. Everything else, index.html included, is checked with the server on every page load, so the
        // browser never shows an old page after an update.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new FrontendResourceResolver());
    }

    static class FrontendResourceResolver extends PathResourceResolver {

        private final Resource indexPage = new ClassPathResource("static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null) {
                return resource;
            }
            return isFrontendRoute(resourcePath) && indexPage.isReadable() ? indexPage : null;
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
