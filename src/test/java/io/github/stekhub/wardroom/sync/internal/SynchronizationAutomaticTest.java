package io.github.stekhub.wardroom.sync.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.TIMEOUT;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.overview;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.overviews;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import java.io.IOException;
import java.time.Duration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * Groups are synchronized without a request of the owner, as in a running installation: here, when a group is
 * subscribed (spec 004). The other integration tests switch this off.
 */
@IntegrationTest
@Import(SynchronizationTestSupport.ShortTimeouts.class)
@TestPropertySource(properties = "wardroom.synchronization.automatic=true")
class SynchronizationAutomaticTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    SynchronizationService synchronizationService;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;
    SynchronizationTestSupport support;

    @BeforeEach
    void startWithServer() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = SynchronizationTestSupport.startMockServer();
        serverId = TestInstallation.addServer(browser, mockServer, 4);
        support = new SynchronizationTestSupport(browser, database, mockServer, serverId);
    }

    /** Nothing is left behind for the schedule, which runs in this test. */
    @AfterEach
    void removeServer() throws IOException {
        support.awaitNoSynchronization();
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    @Test
    void subscribingStartsTheFirstSynchronizationAndRepeatingItChangesNothing() {
        support.setInitialDepth(50);
        mockServer.on("GROUP example.talk").reply("211 200 1 200 example.talk");
        mockServer.on("OVER 151-200").reply(overviews(overview(160, "<a@example.org>"),
                overview(170, "<b@example.org>")).after(Duration.ofMillis(200)));

        int groupId = support.subscribe("example.talk");

        // The request did not wait for the synchronization, which the sidebar shows as running.
        assertThat(support.subscription(groupId).get("sync").get("running").asBoolean()).isTrue();
        assertThat(browser.put("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode subscription = support.awaitSynchronizationEnd(groupId);

        assertThat(subscription.get("sync").get("lastSyncedAt").isNull()).isFalse();
        assertThat(subscription.get("sync").get("error").isNull()).isTrue();
        assertThat(subscription.get("unreadCount").asInt()).isEqualTo(2);
        assertThat(support.ranges(groupId)).containsExactly("151-200");
        assertThat(support.commands("GROUP")).hasSize(1);
        assertThat(database.fetchCount(SUBSCRIPTION)).isEqualTo(1);
    }

    @Test
    void aFirstSynchronizationThatFailsLeavesTheSubscriptionWithTheError() {
        mockServer.on("GROUP example.gone").reply("411 No such newsgroup");

        int groupId = support.subscribe("example.gone");
        JsonNode subscription = support.awaitSynchronizationEnd(groupId);

        assertThat(subscription.get("sync").get("error").asString())
                .isEqualTo("The group is not available on the server.");
        assertThat(database.fetchCount(SUBSCRIPTION)).isEqualTo(1);
    }

    @Test
    void unsubscribingDuringARunningSynchronizationLeavesNoLocationsOfTheGroupBehind() {
        // Ten chunks, each of which takes a while and stores an article.
        support.setInitialDepth(10_000);
        mockServer.on("GROUP example.talk").reply("211 10000 1 10000 example.talk");
        for (int chunk = 0; chunk < 10; chunk++) {
            long firstNumber = chunk * 1000L + 1;
            mockServer.on("OVER " + firstNumber + "-" + (firstNumber + 999))
                    .reply(overviews(overview(firstNumber, "<" + chunk + "@example.org>"))
                            .after(Duration.ofMillis(100)));
        }
        int groupId = support.subscribe("example.talk");
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(5))
                .until(() -> !support.locationNumbers(groupId).isEmpty());

        assertThat(browser.delete("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(database.fetchCount(ARTICLE_LOCATION)).isZero();
        assertThat(database.fetchCount(SYNCED_RANGE)).isZero();
        assertThat(database.fetchCount(ARTICLE)).isZero();
        // The synchronization was cancelled: it ends long before its last chunk, and stores nothing more.
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> synchronizationService.synchronizingGroupIds().isEmpty());
        assertThat(database.fetchCount(ARTICLE_LOCATION)).isZero();
        assertThat(database.fetchCount(SYNCED_RANGE)).isZero();
        assertThat(database.fetchCount(ARTICLE)).isZero();
        assertThat(support.commands("OVER").size()).isLessThan(10);
        assertThat(support.subscriptions()).isEmpty();
    }
}
