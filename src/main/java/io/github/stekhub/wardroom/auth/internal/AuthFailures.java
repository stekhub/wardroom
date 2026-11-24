package io.github.stekhub.wardroom.auth.internal;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The failures of the authentication endpoints. Each is answered as Problem Details with its status. */
final class AuthFailures {

    private AuthFailures() {
    }

    /** Setup is only available while no account exists (ADR 0015). */
    static class SetupUnavailableException extends ErrorResponseException {

        SetupUnavailableException() {
            super(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "An account already exists."), null);
        }
    }

    /** One message for a wrong username and a wrong password, so the answer does not say which one was wrong. */
    static class InvalidCredentialsException extends ErrorResponseException {

        InvalidCredentialsException() {
            super(HttpStatus.UNAUTHORIZED,
                    ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Username or password is wrong."),
                    null);
        }
    }

    /** The second step of a login failed: the code is wrong, or the step took too long (spec 012). */
    static class SecondFactorFailedException extends ErrorResponseException {

        SecondFactorFailedException(String detail) {
            super(HttpStatus.UNAUTHORIZED, ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail), null);
        }
    }

    /** The second factor is not in the state the request needs, for example it is already on (spec 012). */
    static class TwoFactorStateException extends ErrorResponseException {

        TwoFactorStateException(String detail) {
            super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
        }
    }

    /** The login was rejected without checking the password, because of earlier failed logins. */
    static class LoginThrottledException extends ErrorResponseException {

        private final long retryAfterSeconds;

        LoginThrottledException(Duration remainingWait) {
            this(Math.max(1, remainingWait.plusMillis(999).toSeconds()));
        }

        private LoginThrottledException(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed logins. Try again in " + retryAfterSeconds
                            + (retryAfterSeconds == 1 ? " second." : " seconds.")), null);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            return headers;
        }
    }
}
