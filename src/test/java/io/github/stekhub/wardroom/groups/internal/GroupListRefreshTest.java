package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.awaitRefreshEnd;
import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.refreshAndWait;
import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.refreshStatus;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.jooq.tables.records.NewsgroupRecord;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refresh requirements of spec 003, against the whole application, a real SQLite database and the mock NNTP
 * server. Browsing and searching are in {@link GroupListPageTest}.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class GroupListRefreshTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    GroupListSchedule groupListSchedule;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;

    /** Every test starts with a logged-in owner and a server whose mock offers the list and the descriptions. */
    @BeforeEach
    void startWithServer() throws IOException {
        browser = GroupListTestSupport.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        mockServer.on("LIST ACTIVE").reply(MockReply.fixture("list-active.txt"));
        mockServer.on("LIST NEWSGROUPS").reply(MockReply.fixture("list-newsgroups.txt"));
        serverId = GroupListTestSupport.addServer(browser, mockServer);
    }

    /** Removing the server also forgets what is held in memory about its refreshes. */
    @AfterEach
    void removeServer() throws IOException {
        awaitRefreshEnd(browser, serverId);
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Access

    @Test
    void endpointsNeedASessionAndTheCsrfToken() {
        String refreshPath = "/api/servers/" + serverId + "/groups/refresh";

        assertThat(new TestBrowser(mvc).get("/api/servers/" + serverId + "/groups"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).get(refreshPath)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).post(refreshPath)).hasStatus(HttpStatus.UNAUTHORIZED);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        assertThat(secondBrowser.post("/api/auth/login",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(secondBrowser.withoutCsrfToken().post(refreshPath)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mockServer.commands()).doesNotContain("LIST ACTIVE");
    }

    @Test
    void unknownServerIsNotFound() {
        long unknownServerId = serverId + 1;

        assertThat(browser.post("/api/servers/" + unknownServerId + "/groups/refresh"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.get("/api/servers/" + unknownServerId + "/groups/refresh"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // Storing the list

    @Test
    void refreshStoresGroupsWithWatermarksPostingStatusAndDescriptions() {
        long before = System.currentTimeMillis();
        JsonNode statusBefore = refreshStatus(browser, serverId);
        assertThat(statusBefore.get("running").asBoolean()).isFalse();
        assertThat(statusBefore.get("refreshedAt").isNull()).isTrue();
        assertThat(statusBefore.get("error").isNull()).isTrue();

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(status.get("groupsReceived").asInt()).isEqualTo(6);
        assertThat(status.get("refreshedAt").asLong()).isBetween(before, System.currentTimeMillis());
        assertThat(database.selectFrom(NEWSGROUP).orderBy(NEWSGROUP.NAME).fetch())
                .extracting(NewsgroupRecord::getName, NewsgroupRecord::getLowWatermark,
                        NewsgroupRecord::getHighWatermark, NewsgroupRecord::getPostingStatus,
                        NewsgroupRecord::getDescription)
                .containsExactly(
                        tuple("example.alias", 0L, 0L, "NOT_ALLOWED", null),
                        tuple("example.announce", 3L, 9L, "MODERATED", "Announcements, moderated"),
                        tuple("example.archive", 1L, 5000000000L, "NOT_ALLOWED", "The archive, read only"),
                        tuple("example.junk", 12L, 11L, "NOT_ALLOWED", null),
                        tuple("example.local", 1L, 0L, "NOT_ALLOWED", "Not passed on"),
                        tuple("example.talk", 1L, 20L, "ALLOWED", "Grüße und Gespräche"));
        assertThat(database.selectFrom(NEWSGROUP).fetch())
                .allSatisfy(group -> {
                    assertThat(group.getServerId()).isEqualTo((int) serverId);
                    assertThat(group.getRemovedAt()).isNull();
                });
        assertThat(mockServer.commands()).containsSubsequence("LIST ACTIVE", "LIST NEWSGROUPS");
    }

    @Test
    void statusOfListActiveIsMappedToThePostingStatus() {
        assertThat(PostingStatus.ofListActiveStatus("y")).isEqualTo(PostingStatus.ALLOWED);
        assertThat(PostingStatus.ofListActiveStatus("m")).isEqualTo(PostingStatus.MODERATED);
        assertThat(PostingStatus.ofListActiveStatus("n")).isEqualTo(PostingStatus.NOT_ALLOWED);
        assertThat(PostingStatus.ofListActiveStatus("x")).isEqualTo(PostingStatus.NOT_ALLOWED);
        assertThat(PostingStatus.ofListActiveStatus("j")).isEqualTo(PostingStatus.NOT_ALLOWED);
        assertThat(PostingStatus.ofListActiveStatus("=example.talk")).isEqualTo(PostingStatus.NOT_ALLOWED);
        assertThat(PostingStatus.ofListActiveStatus("Y")).as("an unknown value").isEqualTo(PostingStatus.NOT_ALLOWED);
    }

    @Test
    void secondRefreshKeepsTheGroupsAndReplacesWhatTheServerReports() {
        refreshAndWait(browser, serverId);
        int talkId = groupId("example.talk");
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.talk 25 4 m", ".");
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow", "example.talk\tTalk, now moderated", ".");

        refreshAndWait(browser, serverId);

        assertThat(database.selectFrom(NEWSGROUP).where(NEWSGROUP.NAME.eq("example.talk")).fetchSingle())
                .satisfies(group -> {
                    assertThat(group.getId()).as("the group keeps its ID").isEqualTo(talkId);
                    assertThat(group.getLowWatermark()).isEqualTo(4L);
                    assertThat(group.getHighWatermark()).isEqualTo(25L);
                    assertThat(group.getPostingStatus()).isEqualTo("MODERATED");
                    assertThat(group.getDescription()).isEqualTo("Talk, now moderated");
                });
    }

    @Test
    void malformedLineIsSkippedAndTheRefreshContinues(CapturedOutput output) {
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.first 20 1 y", "example.broken 1 y",
                "example.last 7 2 y", "example.first 20 1 y", ".");

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(status.get("groupsReceived").asInt()).as("a group listed twice is one group").isEqualTo(2);
        assertThat(groupNames()).containsExactly("example.first", "example.last");
        assertThat(output).contains("PROTOCOL_ANOMALY kind=list.malformed-active-line");
    }

    // Descriptions

    @Test
    void serverWhoseCapabilitiesLeaveOutListNewsgroupsIsNotAskedAndDescriptionsAreKept() {
        refreshAndWait(browser, serverId);
        mockServer.on("CAPABILITIES").reply("101 Capability list:", "VERSION 2", "READER", "LIST ACTIVE", ".");
        // The pooled connection read the capabilities before; saving the server closes it.
        reconnect();
        int commandsBefore = mockServer.commands().size();

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(mockServer.commands().subList(commandsBefore, mockServer.commands().size()))
                .contains("LIST ACTIVE").doesNotContain("LIST NEWSGROUPS");
        assertThat(description("example.talk")).isEqualTo("Grüße und Gespräche");
    }

    @Test
    void serverThatRejectsListNewsgroupsStillRefreshesAndDescriptionsAreKept() {
        refreshAndWait(browser, serverId);
        mockServer.on("LIST NEWSGROUPS").reply("503 Descriptions are not available");

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(description("example.talk")).isEqualTo("Grüße und Gespräche");
    }

    @Test
    void serverWithoutCapabilitiesRefreshesWithoutDescriptions() {
        mockServer.on("CAPABILITIES").reply("500 What?");
        mockServer.on("LIST NEWSGROUPS").reply("500 What?");
        reconnect();

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(groupNames()).hasSize(6);
        assertThat(database.selectFrom(NEWSGROUP).fetch()).allSatisfy(
                group -> assertThat(group.getDescription()).isNull());
        assertThat(mockServer.commands()).contains("MODE READER", "LIST NEWSGROUPS");
    }

    @Test
    void groupWithoutALineInTheDescriptionsKeepsItsDescription() {
        refreshAndWait(browser, serverId);
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow", "example.announce\tNews of the project",
                "example.unknown\tNot in the group list", ".");

        refreshAndWait(browser, serverId);

        assertThat(description("example.announce")).isEqualTo("News of the project");
        assertThat(description("example.talk")).as("not described this time").isEqualTo("Grüße und Gespräche");
        assertThat(groupNames()).as("a description does not create a group").doesNotContain("example.unknown");
    }

    @Test
    void descriptionThatRepeatsTheGroupNameIsNoDescription(CapturedOutput output) {
        refreshAndWait(browser, serverId);
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow", "example.talk\texample.talk",
                "example.announce\tExample.Announce", "example.local\tStill not passed on", ".");

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(description("example.talk")).as("the stored description is removed").isNull();
        assertThat(description("example.announce")).isNull();
        assertThat(description("example.local")).isEqualTo("Still not passed on");
        assertThat(output).as("a server may do this; it is no anomaly").doesNotContain("list.malformed-description");
    }

    @Test
    void descriptionThatIsGroupListDataIsIgnoredAndReported(CapturedOutput output) {
        refreshAndWait(browser, serverId);
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow",
                "example.talk\t0000015957 0000000001 y", "example.archive\t20 1 =example.talk",
                "example.announce\t3 new rules in 2026", ".");

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(description("example.talk")).isNull();
        assertThat(description("example.archive")).isNull();
        assertThat(description("example.announce")).as("a real description with numbers")
                .isEqualTo("3 new rules in 2026");
        assertThat(output).contains("PROTOCOL_ANOMALY kind=list.malformed-description command=\"LIST NEWSGROUPS\""
                + " group=example.talk action=description-ignored");
    }

    // Removed and returning groups

    @Test
    void groupMissingFromTheListIsMarkedRemovedAndAReturningGroupIsCleared() {
        refreshAndWait(browser, serverId);
        long before = System.currentTimeMillis();
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.talk 20 1 y", ".");

        refreshAndWait(browser, serverId);

        assertThat(removedAt("example.talk")).isNull();
        assertThat(removedAt("example.announce")).isBetween(before, System.currentTimeMillis());
        assertThat(description("example.announce")).as("the row is kept").isEqualTo("Announcements, moderated");
        Long firstRemovedAt = removedAt("example.archive");

        refreshAndWait(browser, serverId);

        assertThat(removedAt("example.archive")).as("the time of the removal is kept").isEqualTo(firstRemovedAt);

        mockServer.on("LIST ACTIVE").reply(MockReply.fixture("list-active.txt"));
        refreshAndWait(browser, serverId);

        assertThat(database.selectFrom(NEWSGROUP).fetch()).hasSize(6)
                .allSatisfy(group -> assertThat(group.getRemovedAt()).isNull());
    }

    @Test
    void refreshThatFailsPartwayMarksNothingAndKeepsTheReceivedGroups() {
        JsonNode firstStatus = refreshAndWait(browser, serverId);
        // More than one part arrives, then the connection breaks before the end of the list.
        String[] lines = new String[GroupListRefresher.BATCH_SIZE + 501];
        lines[0] = "215 Newsgroups follow";
        for (int index = 1; index < lines.length; index++) {
            lines[index] = "partial.group-%04d 10 1 y".formatted(index);
        }
        mockServer.on("LIST ACTIVE").reply(MockReply.cutOff(lines));

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").asString()).isEqualTo("The server closed the connection");
        assertThat(status.get("refreshedAt").asLong()).as("the last successful refresh")
                .isEqualTo(firstStatus.get("refreshedAt").asLong());
        assertThat(database.fetchCount(NEWSGROUP, NEWSGROUP.NAME.startsWith("partial.")))
                .as("the complete parts are stored").isEqualTo(GroupListRefresher.BATCH_SIZE);
        assertThat(database.fetchCount(NEWSGROUP, NEWSGROUP.REMOVED_AT.isNotNull()))
                .as("the groups of the first refresh are not in the broken list, and are not marked").isZero();
        assertThat(database.select(NNTP_SERVER.GROUPS_REFRESH_ERROR).from(NNTP_SERVER).fetchSingle().value1())
                .isEqualTo("The server closed the connection");

        mockServer.on("LIST ACTIVE").reply(MockReply.fixture("list-active.txt"));
        JsonNode nextStatus = refreshAndWait(browser, serverId);

        assertThat(nextStatus.get("error").isNull()).as("a successful refresh clears the error").isTrue();
    }

    // Running state

    @Test
    void onlyOneRefreshRunsAtATimeAndItsProgressIsReported() {
        CountDownLatch restOfTheList = new CountDownLatch(1);
        mockServer.on("LIST ACTIVE").reply(MockReply.inTwoParts(
                new String[] {"215 Newsgroups follow", "example.first 20 1 y", "example.second 5 1 n"},
                restOfTheList, new String[] {"example.third 9 1 m", "."}));
        String refreshPath = "/api/servers/" + serverId + "/groups/refresh";

        assertThat(browser.post(refreshPath)).hasStatus(HttpStatus.ACCEPTED);
        await().atMost(GroupListTestSupport.REFRESH_TIMEOUT)
                .until(() -> refreshStatus(browser, serverId).get("groupsReceived").asInt() == 2);
        assertThat(refreshStatus(browser, serverId).get("running").asBoolean()).isTrue();
        assertThat(browser.post(refreshPath)).as("asked again while it runs").hasStatus(HttpStatus.ACCEPTED);
        groupListSchedule.startDueRefreshes(System.currentTimeMillis());
        restOfTheList.countDown();
        JsonNode status = awaitRefreshEnd(browser, serverId);

        assertThat(status.get("groupsReceived").asInt()).isEqualTo(3);
        assertThat(mockServer.commands().stream().filter("LIST ACTIVE"::equals)).hasSize(1);
    }

    // Failures

    @Test
    void refreshIsRefusedWhileTheCredentialsAreInvalid() {
        database.update(NNTP_SERVER).set(NNTP_SERVER.CREDENTIALS_INVALID, true).execute();

        assertThat(browser.post("/api/servers/" + serverId + "/groups/refresh")).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").asString().contains("server settings");
        assertThat(refreshStatus(browser, serverId).get("running").asBoolean()).isFalse();
        assertThat(mockServer.commands()).doesNotContain("LIST ACTIVE");
    }

    @Test
    void serverThatRefusesServiceFailsTheRefreshWithoutARetry() {
        mockServer.on("LIST ACTIVE").reply("502 Not today");

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").asString()).startsWith("The server refused access");
        assertThat(status.get("refreshedAt").isNull()).isTrue();
        assertThat(mockServer.commands().stream().filter("LIST ACTIVE"::equals)).as("not retried").hasSize(1);
    }

    @Test
    void unreachableServerFailsTheRefreshAndOpensTheCircuitAfterFiveFailures() throws IOException {
        mockServer.close();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(refreshAndWait(browser, serverId).get("error").asString())
                    .as("attempt %s", attempt).startsWith("Cannot connect to localhost:");
        }
        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").asString())
                .isEqualTo("The server failed repeatedly and is not contacted for a while");
    }

    // Schedule (ADR 0020)

    @Test
    void scheduleRefreshesAListThatWasNeverFetched() {
        groupListSchedule.startDueRefreshes(System.currentTimeMillis());

        assertThat(awaitRefreshEnd(browser, serverId).get("refreshedAt").isNull()).isFalse();
        assertThat(groupNames()).hasSize(6);
    }

    @Test
    void scheduleRefreshesOnceTheIntervalSinceTheLastSuccessfulRefreshHasPassed() {
        long refreshedAt = refreshAndWait(browser, serverId).get("refreshedAt").asLong();

        groupListSchedule.startDueRefreshes(refreshedAt + 24 * HOUR.toMillis() - 1);
        awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).as("the interval has not passed").isEqualTo(1);

        groupListSchedule.startDueRefreshes(refreshedAt + 24 * HOUR.toMillis());
        JsonNode status = awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).isEqualTo(2);
        assertThat(status.get("refreshedAt").asLong()).isGreaterThanOrEqualTo(refreshedAt);
    }

    @Test
    void scheduleFollowsTheIntervalOfTheSettings() {
        assertThat(browser.put("/api/settings",
                "{\"syncIntervalMinutes\": 15, \"initialDepth\": 500, \"groupListIntervalHours\": 2,"
                        + " \"cacheLimitEnabled\": true, \"maxCacheSizeGb\": 5}")).hasStatusOk();
        long refreshedAt = refreshAndWait(browser, serverId).get("refreshedAt").asLong();

        groupListSchedule.startDueRefreshes(refreshedAt + 2 * HOUR.toMillis() - 1);
        awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).as("the interval has not passed").isEqualTo(1);

        groupListSchedule.startDueRefreshes(refreshedAt + 2 * HOUR.toMillis());
        awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).isEqualTo(2);
    }

    @Test
    void scheduleTriesAgainOneHourAfterAFailedRefresh() {
        mockServer.on("LIST ACTIVE").reply("502 Not today");
        long before = System.currentTimeMillis();
        groupListSchedule.startDueRefreshes(before);
        assertThat(awaitRefreshEnd(browser, serverId).get("error").isNull()).isFalse();
        long after = System.currentTimeMillis();

        groupListSchedule.startDueRefreshes(before + HOUR.toMillis() - 1);
        awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).as("less than an hour after the failure").isEqualTo(1);

        mockServer.on("LIST ACTIVE").reply(MockReply.fixture("list-active.txt"));
        groupListSchedule.startDueRefreshes(after + HOUR.toMillis());
        JsonNode status = awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).isEqualTo(2);
        assertThat(status.get("error").isNull()).isTrue();
    }

    @Test
    void scheduleLeavesAServerWithInvalidCredentialsAlone() {
        database.update(NNTP_SERVER).set(NNTP_SERVER.CREDENTIALS_INVALID, true).execute();

        groupListSchedule.startDueRefreshes(System.currentTimeMillis());
        awaitRefreshEnd(browser, serverId);

        assertThat(listActiveCommands()).isZero();
    }

    // Large lists

    @Test
    void listOfOneHundredThousandGroupsCompletes() {
        int groupCount = 100_000;
        StringBuilder list = new StringBuilder("215 Newsgroups follow\r\n");
        StringBuilder descriptions = new StringBuilder("215 Descriptions follow\r\n");
        for (int index = 0; index < groupCount; index++) {
            list.append("large.hierarchy.group-").append(index).append(" 0000012345 0000000001 y\r\n");
            descriptions.append("large.hierarchy.group-").append(index).append("\tGroup number ").append(index)
                    .append("\r\n");
        }
        mockServer.on("LIST ACTIVE").reply(MockReply.raw(list.append(".\r\n").toString()
                .getBytes(StandardCharsets.ISO_8859_1)));
        mockServer.on("LIST NEWSGROUPS").reply(MockReply.raw(descriptions.append(".\r\n").toString()
                .getBytes(StandardCharsets.ISO_8859_1)));

        JsonNode status = refreshAndWait(browser, serverId);

        assertThat(status.get("error").isNull()).isTrue();
        assertThat(status.get("groupsReceived").asInt()).isEqualTo(groupCount);
        assertThat(database.fetchCount(NEWSGROUP)).isEqualTo(groupCount);
        assertThat(database.fetchCount(NEWSGROUP, NEWSGROUP.DESCRIPTION.isNull())).isZero();
        assertThat(description("large.hierarchy.group-99999")).isEqualTo("Group number 99999");

        // The next refresh has to find the one group that is gone among the 100,000 stored ones.
        mockServer.on("LIST ACTIVE").reply(MockReply.raw(list.toString()
                .replace("large.hierarchy.group-500 0000012345 0000000001 y\r\n", "")
                .getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(refreshAndWait(browser, serverId).get("error")).hasToString("null");

        assertThat(database.select(NEWSGROUP.NAME).from(NEWSGROUP).where(NEWSGROUP.REMOVED_AT.isNotNull()).fetch()
                .getValues(NEWSGROUP.NAME)).containsExactly("large.hierarchy.group-500");
    }

    // Removing the server (spec 002)

    @Test
    void removingTheServerDeletesItsGroups() throws IOException {
        refreshAndWait(browser, serverId);
        assertThat(database.fetchCount(NEWSGROUP)).isEqualTo(6);

        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(database.fetchCount(NEWSGROUP)).isZero();
        assertThat(database.fetchCount(NNTP_SERVER)).isZero();
        // The cleanup after the test removes a server; give it one.
        serverId = GroupListTestSupport.addServer(browser, mockServer);
    }

    /** Saves the server again, which closes the pooled connections, so the next refresh connects anew. */
    private void reconnect() {
        Map<String, Object> server = Map.of("name", "Mock server", "host", "localhost", "port", mockServer.port(),
                "tlsMode", "NONE", "authMethod", "NONE", "maxConnections", 4);
        assertThat(browser.put("/api/servers/" + serverId,
                JsonMapper.builder().build().writeValueAsString(server))).hasStatusOk();
    }

    private List<String> groupNames() {
        return database.select(NEWSGROUP.NAME).from(NEWSGROUP).orderBy(NEWSGROUP.NAME).fetch(NEWSGROUP.NAME);
    }

    private int groupId(String name) {
        return database.select(NEWSGROUP.ID).from(NEWSGROUP).where(NEWSGROUP.NAME.eq(name)).fetchSingle().value1();
    }

    private String description(String name) {
        return database.select(NEWSGROUP.DESCRIPTION).from(NEWSGROUP).where(NEWSGROUP.NAME.eq(name)).fetchSingle()
                .value1();
    }

    private Long removedAt(String name) {
        return database.select(NEWSGROUP.REMOVED_AT).from(NEWSGROUP).where(NEWSGROUP.NAME.eq(name)).fetchSingle()
                .value1();
    }

    private long listActiveCommands() {
        return mockServer.commands().stream().filter("LIST ACTIVE"::equals).count();
    }
}
