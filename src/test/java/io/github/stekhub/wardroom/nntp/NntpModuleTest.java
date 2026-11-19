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
        try (MockNntpServer server = MockNntpServer.start()) {
            server.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
            NntpServerSettings settings = new NntpServerSettings(42, "localhost", server.port(), NntpTlsMode.NONE,
                    null, null, 2);

            try (NntpClient client = connector.connect(settings)) {
                assertThat(client.capabilities().has("OVER")).isTrue();
            }
            boolean reader = connections.interactive(settings, client -> client.capabilities().has("READER"));
            connections.reset(settings.serverId());

            assertThat(reader).isTrue();
            assertThat(server.connectionCount()).isEqualTo(2);
        }
    }
}
