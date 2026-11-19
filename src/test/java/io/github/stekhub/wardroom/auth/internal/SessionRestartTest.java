package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.WardroomApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the application twice on the same data directory, with a real HTTP port, to show that a session survives a
 * restart (spec 001). It also covers what only a real server does: the cookie attributes and the forwarded headers of
 * ADR 0010.
 */
class SessionRestartTest {

    private static final String CREDENTIALS = "{\"username\": \"owner\", \"password\": \"correct horse battery\"}";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void sessionSurvivesAnApplicationRestart(@TempDir Path dataDirectory) throws Exception {
        String sessionCookie;
        try (ConfigurableApplicationContext application = start(dataDirectory)) {
            String baseUrl = baseUrl(application);
            String csrfToken = csrfToken(baseUrl);
            HttpResponse<String> setup = setUp(baseUrl, csrfToken, false);
            assertThat(setup.statusCode()).isEqualTo(201);
            String setCookie = sessionSetCookieHeader(setup);
            assertThat(setCookie)
                    .contains("HttpOnly")
                    .contains("SameSite=Lax")
                    .as("the cookie outlives the browser session; the server enforces the 30 days")
                    .contains("Max-Age=34560000")
                    .doesNotContain("Secure");
            sessionCookie = cookiePair(setCookie);
            assertThat(currentAccount(baseUrl, sessionCookie).statusCode()).isEqualTo(200);
        }

        try (ConfigurableApplicationContext application = start(dataDirectory)) {
            HttpResponse<String> afterRestart = currentAccount(baseUrl(application), sessionCookie);

            assertThat(afterRestart.statusCode()).isEqualTo(200);
            assertThat(afterRestart.body()).contains("\"owner\"");
        }
    }

    /** The test client is a local address, like a reverse proxy on the same host or in the same private network. */
    @Test
    void sessionCookieIsSecureWhenAReverseProxyReportsHttps(@TempDir Path dataDirectory) throws Exception {
        try (ConfigurableApplicationContext application = start(dataDirectory)) {
            String baseUrl = baseUrl(application);

            HttpResponse<String> setup = setUp(baseUrl, csrfToken(baseUrl), true);

            assertThat(setup.statusCode()).isEqualTo(201);
            assertThat(sessionSetCookieHeader(setup)).contains("Secure").contains("HttpOnly");
        }
    }

    private static ConfigurableApplicationContext start(Path dataDirectory) {
        return new SpringApplicationBuilder(WardroomApplication.class)
                .profiles("test")
                .run("--wardroom.data-dir=" + dataDirectory, "--server.port=0");
    }

    private static String baseUrl(ConfigurableApplicationContext application) {
        return "http://localhost:" + application.getEnvironment().getProperty("local.server.port");
    }

    private String csrfToken(String baseUrl) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/setup")).build(),
                HttpResponse.BodyHandlers.ofString());
        String setCookie = setCookieHeader(response, "XSRF-TOKEN");
        return cookiePair(setCookie).substring("XSRF-TOKEN=".length());
    }

    private HttpResponse<String> setUp(String baseUrl, String csrfToken, boolean forwardedHttps)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/setup"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(CREDENTIALS));
        if (forwardedHttps) {
            request.header("X-Forwarded-Proto", "https");
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> currentAccount(String baseUrl, String sessionCookie)
            throws IOException, InterruptedException {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/me")).header("Cookie", sessionCookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionSetCookieHeader(HttpResponse<String> response) {
        return setCookieHeader(response, "SESSION");
    }

    private static String setCookieHeader(HttpResponse<String> response, String cookieName) {
        List<String> setCookieHeaders = response.headers().allValues("Set-Cookie");
        return setCookieHeaders.stream()
                .filter(header -> header.startsWith(cookieName + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + cookieName + " cookie in " + setCookieHeaders.size()
                        + " Set-Cookie headers"));
    }

    /** @return the {@code name=value} part of a {@code Set-Cookie} header */
    private static String cookiePair(String setCookieHeader) {
        int end = setCookieHeader.indexOf(';');
        return end < 0 ? setCookieHeader : setCookieHeader.substring(0, end);
    }
}
