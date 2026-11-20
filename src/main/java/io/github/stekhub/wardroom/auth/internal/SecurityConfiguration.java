package io.github.stekhub.wardroom.auth.internal;

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

/**
 * Access rules and session handling (spec 001).
 *
 * <ul>
 * <li>Setup and login are public. Every other path below {@code /api} needs a session and answers {@code 401}
 * without one; nothing redirects.</li>
 * <li>Everything outside {@code /api} is public: the frontend's files and the health endpoint.</li>
 * <li>Every request that changes state needs the CSRF token. The token is in a cookie the frontend can read, and
 * the frontend sends it back in a request header.</li>
 * </ul>
 *
 * <p>Login and logout are done by {@link AuthController}, not by Spring Security's own login form and logout filter.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
            SessionSecurityContextRepository securityContextRepository, ProblemResponseWriter problemResponseWriter)
            throws Exception {
        httpSecurity.authorizeHttpRequests(requests -> requests
                .requestMatchers("/api/auth/setup").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
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
        httpSecurity.formLogin(AbstractHttpConfigurer::disable);
        httpSecurity.httpBasic(AbstractHttpConfigurer::disable);
        httpSecurity.logout(AbstractHttpConfigurer::disable);
        httpSecurity.requestCache(AbstractHttpConfigurer::disable);
        return httpSecurity.build();
    }

    /** Argon2id with Spring Security's default parameters. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
