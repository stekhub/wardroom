package io.github.stekhub.wardroom.platform.web;

import io.github.stekhub.wardroom.platform.LogSafe;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Answers every API error as Problem Details (RFC 9457). The base class covers the errors Spring MVC raises itself,
 * such as validation failures and unknown paths.
 */
@Slf4j
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * An error nobody expected. It is logged with method, path and stack trace (ADR 0029); the response says nothing
     * about the cause, because the message could contain internal details.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected error in {} {}", LogSafe.escape(request.getMethod()),
                LogSafe.escape(request.getRequestURI()), exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. The server log has the details.");
    }
}
