package io.github.stekhub.wardroom.platform.web;

import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.platform.LogSafe;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Answers every API error as Problem Details (RFC 9457). The base class covers the errors Spring MVC raises itself,
 * such as unknown paths.
 *
 * <p>A validation failure is a {@code 400} whose {@code detail} is the first failed rule and whose {@code errors}
 * property lists every failed field with its rule.
 */
@Slf4j
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** One failed field of a validation error. */
    record FieldError(String field, String message) {
    }

    @ExceptionHandler(FieldValidationException.class)
    ProblemDetail handleFieldValidation(FieldValidationException exception) {
        return validationProblem(List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldError(fieldError.getField(),
                        fieldError.getField() + " " + fieldError.getDefaultMessage()))
                .toList();
        if (fieldErrors.isEmpty()) {
            return super.handleMethodArgumentNotValid(exception, headers, status, request);
        }
        return handleExceptionInternal(exception, validationProblem(fieldErrors), headers, status, request);
    }

    /**
     * An error nobody expected. It is logged with method, path and stack trace (ADR 0029); the response says nothing
     * about the cause, because the message could contain internal details.
     *
     * <p>A browser that closes the connection before it has the whole response is not an error of the application:
     * it happens when a page is reloaded or left. Nobody is there to read an answer, so there is none, and the log
     * gets one line at {@code DEBUG}.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            log.debug("The client closed the connection during {} {}", LogSafe.escape(request.getMethod()),
                    LogSafe.escape(request.getRequestURI()));
            return null;
        }
        log.error("Unexpected error in {} {}", LogSafe.escape(request.getMethod()),
                LogSafe.escape(request.getRequestURI()), exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. The server log has the details.");
    }

    private static ProblemDetail validationProblem(List<FieldError> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                fieldErrors.getFirst().message());
        problem.setProperty("errors", fieldErrors);
        return problem;
    }
}
