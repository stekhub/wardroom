package io.github.stekhub.wardroom.ignoring.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the ignore rule endpoints. Each is answered as Problem Details with its status. */
final class IgnoreFailures {

    private IgnoreFailures() {
    }

    static class ArticleNotFoundException extends ErrorResponseException {

        ArticleNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This article does not exist."), null);
        }
    }

    static class GroupNotFoundException extends ErrorResponseException {

        GroupNotFoundException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This group does not exist."), null);
        }
    }
}
