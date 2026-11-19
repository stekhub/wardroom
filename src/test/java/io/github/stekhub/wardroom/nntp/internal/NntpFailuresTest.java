package io.github.stekhub.wardroom.nntp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.nntp.NNTPConnectionClosedException;
import org.junit.jupiter.api.Test;

/** The mapping from I/O errors to failure types, for the cases that are hard to produce with a real socket. */
class NntpFailuresTest {

    @Test
    void unknownHostIsUnreachable() {
        assertThat(map(new UnknownHostException("news.example.invalid")).type())
                .isEqualTo(NntpFailureType.UNREACHABLE);
    }

    @Test
    void refusedConnectionAndMissingRouteAreUnreachable() {
        assertThat(map(new ConnectException("Connection refused")).type()).isEqualTo(NntpFailureType.UNREACHABLE);
        assertThat(map(new NoRouteToHostException("No route to host")).type())
                .isEqualTo(NntpFailureType.UNREACHABLE);
    }

    @Test
    void connectAndReadTimeoutsAreTimeouts() {
        assertThat(map(new SocketTimeoutException("Connect timed out")).type()).isEqualTo(NntpFailureType.TIMEOUT);
        assertThat(map(new SocketTimeoutException("Read timed out")).type()).isEqualTo(NntpFailureType.TIMEOUT);
    }

    @Test
    void handshakeFailureCausedByTheCertificateIsACertificateFailure() {
        SSLHandshakeException failure = new SSLHandshakeException("PKIX path building failed");
        failure.initCause(new CertificateException("unable to find valid certification path"));

        NntpException mapped = map(failure);

        assertThat(mapped.type()).isEqualTo(NntpFailureType.TLS_CERTIFICATE);
        assertThat(mapped).hasMessageContaining("unable to find valid certification path");
    }

    @Test
    void otherTlsFailureIsAHandshakeFailure() {
        assertThat(map(new SSLException("Unsupported or unrecognized SSL message")).type())
                .isEqualTo(NntpFailureType.TLS_HANDSHAKE);
    }

    @Test
    void response400IsRefusedAndASilentCloseIsConnectionLost() {
        assertThat(map(new NNTPConnectionClosedException("NNTP response 400 received.  Server closed connection."))
                .type()).isEqualTo(NntpFailureType.SERVER_REFUSED);
        assertThat(map(new NNTPConnectionClosedException("Connection closed without indication.")).type())
                .isEqualTo(NntpFailureType.CONNECTION_LOST);
    }

    @Test
    void malformedReplyIsAProtocolFailure() {
        assertThat(map(new MalformedServerReplyException("Truncated server reply: x")).type())
                .isEqualTo(NntpFailureType.PROTOCOL);
    }

    @Test
    void otherIoErrorsAreConnectionLost() {
        assertThat(map(new SocketException("Connection reset")).type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        assertThat(map(new EOFException()).type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
    }

    @Test
    void hostNameInTheMessageIsEscaped() {
        NntpException mapped = NntpFailures.fromIo(new UnknownHostException("x"), "bad\nhost", 119);

        assertThat(mapped.getMessage()).doesNotContain("\n").contains("bad\\nhost");
    }

    private static NntpException map(IOException e) {
        return NntpFailures.fromIo(e, "news.example.com", 563);
    }
}
