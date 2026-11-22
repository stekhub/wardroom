package io.github.stekhub.wardroom.posting.internal;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the posting endpoints. Each is answered as Problem Details with its status. */
final class PostingFailures {

    private PostingFailures() {
    }

    static class ServerNotFoundException extends ErrorResponseException {

        ServerNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This server does not exist."), null);
        }
    }

    /** No group has the ID, or the group is not subscribed. */
    static class GroupNotFoundException extends ErrorResponseException {

        GroupNotFoundException() {
            super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "This group does not exist or is not subscribed."), null);
        }
    }

    /** No article has the ID of the parent. */
    static class ParentNotFoundException extends ErrorResponseException {

        ParentNotFoundException() {
            super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "The article you reply to does not exist."), null);
        }
    }

    /** The article cannot be posted as things are (spec 009): the request itself is valid. */
    static class PostingNotPossibleException extends ErrorResponseException {

        /** @param reason the reason in words for the owner */
        PostingNotPossibleException(String reason) {
            super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, reason), null);
        }
    }

    /** Why the server did not take the article (spec 009, "Sending and errors"). */
    enum FailureType {
        POSTING_NOT_ALLOWED,
        AUTH_REQUIRED,
        POSTING_FAILED,
        UNREACHABLE
    }

    /**
     * The article was not posted. The answer carries the kind of failure as its {@code type}, the message of the
     * server as its detail, and the Message-ID to try again with.
     */
    static class PostingFailedException extends ErrorResponseException {

        /**
         * @param serverMessage what the server said, or the cause in words; never contains credentials
         * @param messageId     the Message-ID of the article that was not posted
         */
        PostingFailedException(FailureType failureType, String serverMessage, String messageId) {
            super(HttpStatus.BAD_GATEWAY, problem(failureType, serverMessage, messageId), null);
        }

        private static ProblemDetail problem(FailureType failureType, String serverMessage, String messageId) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, serverMessage);
            problem.setType(URI.create(failureType.name()));
            problem.setProperty("messageId", messageId);
            return problem;
        }
    }
}
