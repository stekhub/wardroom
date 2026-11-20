package io.github.stekhub.wardroom.sync.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the synchronization endpoints. Each is answered as Problem Details with its status. */
final class SynchronizationFailures {

    private SynchronizationFailures() {
    }

    static class GroupNotFoundException extends ErrorResponseException {

        GroupNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This group does not exist."), null);
        }
    }

    static class GroupNotSubscribedException extends ErrorResponseException {

        GroupNotSubscribedException() {
            super(HttpStatus.CONFLICT,
                    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This group is not subscribed."), null);
        }
    }

    /** A synchronization would only be another rejected login (spec 005). */
    static class CredentialsInvalidException extends ErrorResponseException {

        CredentialsInvalidException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Wardroom cannot log in to the server. Enter the username and password again in the server "
                            + "settings."), null);
        }
    }
}
