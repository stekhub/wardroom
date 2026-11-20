package io.github.stekhub.wardroom.server.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the server endpoints. Each is answered as Problem Details with its status. */
final class ServerFailures {

    private ServerFailures() {
    }

    static class ServerNotFoundException extends ErrorResponseException {

        ServerNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This server does not exist."), null);
        }
    }

    /** The MVP allows exactly one server (ADR 0012). */
    static class ServerExistsException extends ErrorResponseException {

        ServerExistsException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "A server is already configured. Wardroom works with one server."), null);
        }
    }
}
