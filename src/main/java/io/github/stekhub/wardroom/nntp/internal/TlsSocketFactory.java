package io.github.stekhub.wardroom.nntp.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Creates the sockets for implicit TLS (ADR 0016). A plain {@link SSLSocketFactory} checks that the certificate is
 * trusted, but not that it belongs to the host. This factory turns the host name check on for every socket.
 */
final class TlsSocketFactory extends SocketFactory {

    private final SSLSocketFactory delegate;
    private final String host;

    TlsSocketFactory(SSLSocketFactory delegate, String host) {
        this.delegate = delegate;
        this.host = host;
    }

    @Override
    public Socket createSocket() throws IOException {
        return verifying(delegate.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return verifying(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return verifying(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return verifying(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return verifying(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket verifying(Socket socket) {
        SSLSocket tls = (SSLSocket) socket;
        SSLParameters parameters = tls.getSSLParameters();
        // "HTTPS" is the name of the standard host name check (RFC 2818); it is not specific to HTTP.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (isHostName(host)) {
            // The server name is sent in the handshake, so a server with several certificates picks the right one.
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        tls.setSSLParameters(parameters);
        return tls;
    }

    private static boolean isHostName(String host) {
        try {
            InetAddress.ofLiteral(host);
            return false;
        } catch (IllegalArgumentException notAnIpAddress) {
            try {
                new SNIHostName(host);
                return true;
            } catch (IllegalArgumentException notAValidName) {
                return false;
            }
        }
    }
}
