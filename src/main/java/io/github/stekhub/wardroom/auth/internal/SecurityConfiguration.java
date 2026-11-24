package io.github.stekhub.wardroom.auth.internal;

import java.time.InstantSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Access rules and session handling (spec 001).
 *
 * <ul>
 * <li>Setup and both steps of the login are public. Every other path below {@code /api} needs a session and
 * answers {@code 401} without one; nothing redirects.</li>
 * <li>Everything outside {@code /api} is public: the frontend's files and the health endpoint.</li>
 * <li>Every response carries the Content Security Policy, {@code X-Content-Type-Options: nosniff} and
 * {@code Referrer-Policy: no-referrer} (spec 007).</li>
 * <li>Every request that changes state needs the CSRF token. The token is in a cookie the frontend can read, and
 * the frontend sends it back in a request header.</li>
 * </ul>
 *
 * <p>Login and logout are done by {@link AuthController}, not by Spring Security's own login form and logout filter.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /**
     * What a page of Wardroom may load and run (spec 007, "Security considerations"): everything only from
     * Wardroom itself; no inline scripts; inline styles, because UI components insert their own; images also from
     * {@code data:} addresses; and no page inside a frame. Article content is untrusted, and this is the second
     * line of defense if escaping it ever fails.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
            SessionSecurityContextRepository securityContextRepository, ProblemResponseWriter problemResponseWriter)
            throws Exception {
        httpSecurity.authorizeHttpRequests(requests -> requests
                .requestMatchers("/api/auth/setup").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/login/second-factor").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll());
        httpSecurity.securityContext(
                securityContext -> securityContext.securityContextRepository(securityContextRepository));
        httpSecurity.csrf(CsrfConfigurer::spa);
        httpSecurity.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> problemResponseWriter.write(response,
                        HttpStatus.UNAUTHORIZED, "Log in to use this endpoint."))
                .accessDeniedHandler((request, response, exception) -> problemResponseWriter.write(response,
                        HttpStatus.FORBIDDEN, "The CSRF token is missing or invalid.")));
        httpSecurity.headers(headers -> headers
                .contentSecurityPolicy(policy -> policy.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(policy -> policy.policy(ReferrerPolicy.NO_REFERRER)));
        httpSecurity.formLogin(AbstractHttpConfigurer::disable);
        httpSecurity.httpBasic(AbstractHttpConfigurer::disable);
        httpSecurity.logout(AbstractHttpConfigurer::disable);
        httpSecurity.requestCache(AbstractHttpConfigurer::disable);
        return httpSecurity.build();
    }

    /** The time for the login throttling and the second factor. Tests put a clock they control in its place. */
    @Bean
    InstantSource loginClock() {
        return InstantSource.system();
    }

    /** Argon2id with Spring Security's default parameters. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
