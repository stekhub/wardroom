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

    static class RangeNotFoundException extends ErrorResponseException {

        RangeNotFoundException() {
            super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "This group has no such range of articles."), null);
        }
    }

    /** Loading starts from what the synchronization has stored; before that there is nothing to start from. */
    static class GroupNotSynchronizedException extends ErrorResponseException {

        GroupNotSynchronizedException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This group has not been synchronized yet. Refresh it first."), null);
        }
    }

    /** The article numbers of the stored ranges mean other articles now; the synchronization resets the group. */
    static class GroupRenumberedException extends ErrorResponseException {

        GroupRenumberedException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The server has renumbered this group. Refresh it first."), null);
        }
    }

    /** A synchronization of the group did not end in time. */
    static class GroupBusyException extends ErrorResponseException {

        GroupBusyException() {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This group is being synchronized. Try again when the synchronization has ended."), null);
        }
    }

    /** Loading or jumping failed on the server; nothing was stored (spec 005). */
    static class ServerFailedException extends ErrorResponseException {

        /** @param cause the cause in words for the owner; never contains credentials */
        ServerFailedException(String cause) {
            super(HttpStatus.BAD_GATEWAY, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, cause), null);
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
