package io.github.stekhub.wardroom.groups.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the group endpoints. Each is answered as Problem Details with its status. */
final class GroupFailures {

    private GroupFailures() {
    }

    static class ServerNotFoundException extends ErrorResponseException {

        ServerNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This server does not exist."), null);
        }
    }

    /** A refresh would only be another rejected login (spec 003). */
    static class CredentialsInvalidException extends ErrorResponseException {

        CredentialsInvalidException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Wardroom cannot log in to the server. Enter the username and password again in the server "
                            + "settings."), null);
        }
    }
}
