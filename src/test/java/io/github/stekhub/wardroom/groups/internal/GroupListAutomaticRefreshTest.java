package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.awaitRefreshEnd;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The group list is fetched without a request of the owner, as in a running installation. The other integration
 * tests switch this off.
 */
@IntegrationTest
@TestPropertySource(properties = "wardroom.group-list.automatic-refresh=true")
class GroupListAutomaticRefreshTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Test
    void creatingAServerStartsARefresh() throws IOException {
        TestBrowser browser = GroupListTestSupport.loggedInOwner(mvc, database);
        try (MockNntpServer mockServer = MockNntpServer.start()) {
            mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
            mockServer.on("LIST ACTIVE").reply(MockReply.fixture("list-active.txt"));
            mockServer.on("LIST NEWSGROUPS").reply(MockReply.fixture("list-newsgroups.txt"));

            long serverId = GroupListTestSupport.addServer(browser, mockServer);
            JsonNode status = awaitRefreshEnd(browser, serverId);

            // Without a refresh, the status would say so: no time, no error, nothing received.
            assertThat(status.get("error").isNull()).isTrue();
            assertThat(status.get("refreshedAt").isNull()).isFalse();
            assertThat(status.get("groupsReceived").asInt()).isEqualTo(6);
            assertThat(database.fetchCount(NEWSGROUP)).isEqualTo(6);

            // Nothing is left behind for the schedule, which runs in this test.
            assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        }
    }
}
