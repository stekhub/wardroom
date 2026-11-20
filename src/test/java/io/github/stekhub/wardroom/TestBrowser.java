package io.github.stekhub.wardroom;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends requests like a browser does: it keeps the cookies it receives, and it sends the CSRF token of the cookie
 * back in the request header on every request that changes state. Two instances are two browsers.
 *
 * <p>The attributes of the session cookie are not visible here: they are set by the web server, which these tests
 * do not start. {@code SessionRestartTest} checks them with a real server.
 */
public final class TestBrowser {

    public static final String SESSION_COOKIE = "SESSION";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvcTester mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private boolean sendsCsrfToken = true;

    public TestBrowser(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /** @return a JSON object with the given names and values, in pairs */
    public static String json(String... namesAndValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return JSON.writeValueAsString(fields);
    }

    /** From now on, requests that change state are sent without the CSRF token header. */
    public TestBrowser withoutCsrfToken() {
        sendsCsrfToken = false;
        return this;
    }

    public MvcTestResult get(String path) {
        return exchange(mvc.get().uri(path));
    }

    /** Sends the query parameters as a browser does: every value is encoded, whatever characters it has. */
    public MvcTestResult get(String path, Map<String, String> queryParameters) {
        MockMvcRequestBuilder request = mvc.get().uri(path);
        queryParameters.forEach(request::queryParam);
        return exchange(request);
    }

    public MvcTestResult post(String path) {
        return exchange(withCsrfToken(mvc.post().uri(path)));
    }

    public MvcTestResult post(String path, String jsonBody) {
        return exchange(withCsrfToken(mvc.post().uri(path)).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
    }

    public MvcTestResult put(String path, String jsonBody) {
        return exchange(withCsrfToken(mvc.put().uri(path)).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
    }

    public MvcTestResult delete(String path) {
        return exchange(withCsrfToken(mvc.delete().uri(path)));
    }

    /** @return the value of the cookie, or {@code null} if the browser does not have it */
    public String cookieValue(String name) {
        Cookie cookie = cookies.get(name);
        return cookie == null ? null : cookie.getValue();
    }

    public void setCookie(String name, String value) {
        cookies.put(name, new Cookie(name, value));
    }

    private MockMvcRequestBuilder withCsrfToken(MockMvcRequestBuilder request) {
        if (!sendsCsrfToken) {
            return request;
        }
        if (!cookies.containsKey(CSRF_COOKIE)) {
            // A browser gets the token cookie with its first request, which is always a GET.
            get("/api/auth/setup");
        }
        return request.header(CSRF_HEADER, cookieValue(CSRF_COOKIE));
    }

    private MvcTestResult exchange(MockMvcRequestBuilder request) {
        if (!cookies.isEmpty()) {
            request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        MvcTestResult result = request.exchange();
        MockHttpServletResponse response = result.getResponse();
        for (Cookie cookie : response.getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), new Cookie(cookie.getName(), cookie.getValue()));
            }
        }
        return result;
    }
}
