package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.platform.LogSafe;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.nntp.NNTPConnectionClosedException;

/** Turns I/O errors of the NNTP library into Wardroom's failure types (ADR 0006, spec 002). */
final class NntpFailures {

    private static final int DETAIL_MAX_LENGTH = 200;

    private NntpFailures() {
    }

    static NntpException fromIo(IOException e, String host, int port) {
        String target = LogSafe.escape(host) + ":" + port;
        return switch (e) {
            case MalformedServerReplyException malformed -> new NntpException(NntpFailureType.PROTOCOL,
                    "The server sent a response that is not valid NNTP", malformed);
            case NNTPConnectionClosedException closed when isServiceDiscontinued(closed) -> new NntpException(
                    NntpFailureType.SERVER_REFUSED, "The server refused service (400)", closed);
            case NNTPConnectionClosedException closed -> new NntpException(NntpFailureType.CONNECTION_LOST,
                    "The server closed the connection", closed);
            case UnknownHostException unknown -> new NntpException(NntpFailureType.UNREACHABLE,
                    "Host not found: " + LogSafe.escape(host), unknown);
            case ConnectException refused -> new NntpException(NntpFailureType.UNREACHABLE,
                    "Cannot connect to " + target + ": " + detail(refused), refused);
            case NoRouteToHostException noRoute -> new NntpException(NntpFailureType.UNREACHABLE,
                    "Cannot connect to " + target + ": " + detail(noRoute), noRoute);
            case SocketTimeoutException timeout -> new NntpException(NntpFailureType.TIMEOUT,
                    "The server " + target + " did not answer in time", timeout);
            case SSLException tlsFailure when isCertificateProblem(tlsFailure) -> new NntpException(
                    NntpFailureType.TLS_CERTIFICATE,
                    "The certificate of " + target + " is not trusted: " + detail(rootCause(tlsFailure)), tlsFailure);
            case SSLException tlsFailure -> new NntpException(NntpFailureType.TLS_HANDSHAKE,
                    "Could not set up TLS with " + target + ": " + detail(tlsFailure), tlsFailure);
            default -> new NntpException(NntpFailureType.CONNECTION_LOST,
                    "The connection to " + target + " failed: " + detail(e), e);
        };
    }

    /** The library reports a {@code 400} response and a silently closed connection with the same exception type. */
    private static boolean isServiceDiscontinued(NNTPConnectionClosedException e) {
        return e.getMessage() != null && e.getMessage().contains("400");
    }

    private static boolean isCertificateProblem(SSLException e) {
        if (e instanceof SSLPeerUnverifiedException) {
            return true;
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static String detail(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return LogSafe.escape(message, DETAIL_MAX_LENGTH);
    }
}
