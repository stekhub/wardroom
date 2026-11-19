package io.github.stekhub.wardroom.nntp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The NNTP module as other modules use it: through the interfaces the running application provides. */
@IntegrationTest
class NntpModuleTest {

    @Autowired
    private NntpConnector connector;

    @Autowired
    private NntpConnections connections;

    @Test
    void applicationProvidesTheConnectorAndThePooledConnections() throws IOException {
        try (MockNntpServer mockServer = MockNntpServer.start()) {
            mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
            NntpServerSettings serverSettings = new NntpServerSettings(42, "localhost", mockServer.port(),
                    NntpTlsMode.NONE, null, null, 2);

            try (NntpClient client = connector.connect(serverSettings)) {
                assertThat(client.capabilities().has("OVER")).isTrue();
            }
            boolean reader = connections.interactive(serverSettings, client -> client.capabilities().has("READER"));
            connections.reset(serverSettings.serverId());

            assertThat(reader).isTrue();
            assertThat(mockServer.connectionCount()).isEqualTo(2);
        }
    }
}
