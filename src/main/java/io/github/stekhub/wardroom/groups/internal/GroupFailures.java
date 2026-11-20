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

    static class GroupNotFoundException extends ErrorResponseException {

        GroupNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This group does not exist."), null);
        }
    }

    /** A group that the server no longer lists cannot be subscribed to (spec 004). */
    static class GroupRemovedException extends ErrorResponseException {

        GroupRemovedException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The server no longer has this group, so it cannot be subscribed to."), null);
        }
    }

    static class GroupNotSubscribedException extends ErrorResponseException {

        GroupNotSubscribedException() {
            super(HttpStatus.CONFLICT,
                    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This group is not subscribed."), null);
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
