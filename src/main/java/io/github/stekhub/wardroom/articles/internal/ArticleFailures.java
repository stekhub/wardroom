package io.github.stekhub.wardroom.articles.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the article endpoints. Each is answered as Problem Details with its status. */
final class ArticleFailures {

    private ArticleFailures() {
    }

    static class ArticleNotFoundException extends ErrorResponseException {

        ArticleNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This article does not exist."), null);
        }
    }

    /** The discussion view shows subscribed groups only (spec 008). */
    static class GroupNotFoundException extends ErrorResponseException {

        GroupNotFoundException() {
            super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "This group does not exist or is not subscribed."), null);
        }
    }

    static class GroupNotSubscribedException extends ErrorResponseException {

        GroupNotSubscribedException() {
            super(HttpStatus.CONFLICT,
                    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This group is not subscribed."), null);
        }
    }

    /** No article has the ID, or its thread has no article in the group (spec 008). */
    static class ThreadNotFoundException extends ErrorResponseException {

        ThreadNotFoundException() {
            super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "This discussion is no longer available."), null);
        }
    }

    /** The server answered that it has no such article, now or at an earlier attempt (spec 007). */
    static class ContentUnavailableException extends ErrorResponseException {

        ContentUnavailableException() {
            super(HttpStatus.GONE, ProblemDetail.forStatusAndDetail(HttpStatus.GONE,
                    "This article is no longer available on the server."), null);
        }
    }

    /** The content could not be fetched; nothing was stored (spec 007). */
    static class ServerFailedException extends ErrorResponseException {

        /** @param cause the cause in words for the owner; never contains credentials */
        ServerFailedException(String cause) {
            super(HttpStatus.BAD_GATEWAY, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, cause), null);
        }
    }
}
