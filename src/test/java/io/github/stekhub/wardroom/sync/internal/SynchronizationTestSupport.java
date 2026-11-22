package io.github.stekhub.wardroom.sync.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.internal.TestNntpConnectors;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.jooq.DSLContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** What the synchronization tests share: a scripted mock server, groups, and waiting for the end of the work. */
final class SynchronizationTestSupport {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The date of every scripted overview, and what it is in milliseconds. */
    static final String OVERVIEW_DATE = "Wed, 18 Nov 2026 11:06:40 +0000";
    static final long OVERVIEW_DATE_MILLIS = 1_795_000_000_000L;

    private final TestBrowser browser;
    private final DSLContext database;
    private final MockNntpServer mockServer;
    private final long serverId;

    SynchronizationTestSupport(TestBrowser browser, DSLContext database, MockNntpServer mockServer, long serverId) {
        this.browser = browser;
        this.database = database;
        this.mockServer = mockServer;
        this.serverId = serverId;
    }

    /** The application with pooled connections that fail fast, so the failure cases do not slow the build down. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ShortTimeouts {

        @Bean
        @Primary
        NntpConnections shortTimeoutConnections() {
            return TestNntpConnectors.pooledWithShortTimeouts();
        }
    }

    /** A mock that offers {@code OVER} with the field order of RFC 3977 and has no article in any range. */
    static MockNntpServer startMockServer() throws java.io.IOException {
        MockNntpServer mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        mockServer.on("LIST OVERVIEW.FMT").reply("215 Order of fields in overview database.", "Subject:", "From:",
                "Date:", "Message-ID:", "References:", ":bytes", ":lines", "Xref:full", ".");
        mockServer.on("OVER").reply("224 Overview information follows", ".");
        return mockServer;
    }

    /** One line of an overview answer. */
    static String overview(long number, String subject, String messageId, String references) {
        return number + "\t" + subject + "\tAda <ada@example.org>\t" + OVERVIEW_DATE + "\t" + messageId + "\t"
                + references + "\t900\t12";
    }

    static String overview(long number, String messageId) {
        return overview(number, "Subject " + number, messageId, "");
    }

    /** One line of an overview answer for an article with a date of its own, given in UTC milliseconds. */
    static String datedOverview(long number, String messageId, long dateMillis) {
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(dateMillis).atOffset(java.time.ZoneOffset.UTC));
        return number + "\tSubject " + number + "\tAda <ada@example.org>\t" + date + "\t" + messageId
                + "\t\t900\t12";
    }

    /** The answer to {@code OVER} for one range. */
    static MockReply overviews(String... lines) {
        List<String> answer = new ArrayList<>();
        answer.add("224 Overview information follows");
        answer.addAll(List.of(lines));
        answer.add(".");
        return MockReply.lines(answer.toArray(String[]::new));
    }

    /** Stores a group as the group list would, and subscribes to it like the owner does. */
    int subscribe(String groupName) {
        int groupId = database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, groupName)
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 0L)
                .set(NEWSGROUP.HIGH_WATERMARK, 0L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
        assertThat(browser.put("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);
        return groupId;
    }

    void setInitialDepth(int initialDepth) {
        assertThat(browser.put("/api/settings", "{\"syncIntervalMinutes\": 15, \"initialDepth\": " + initialDepth
                + ", \"groupListIntervalHours\": 24, \"cacheLimitEnabled\": true, \"maxCacheSizeGb\": 5}"))
                .hasStatusOk();
    }

    /** Presses the refresh button of the group and waits until its synchronization has ended. */
    JsonNode synchronize(int groupId) {
        assertThat(browser.post("/api/groups/" + groupId + "/sync")).hasStatus(HttpStatus.ACCEPTED);
        return awaitSynchronizationEnd(groupId);
    }

    JsonNode awaitSynchronizationEnd(int groupId) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> !subscription(groupId).get("sync").get("running").asBoolean());
        return subscription(groupId);
    }

    void awaitNoSynchronization() {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> subscriptions().stream().noneMatch(entry -> entry.get("sync").get("running").asBoolean()));
    }

    List<JsonNode> subscriptions() {
        MvcTestResult answer = browser.get("/api/subscriptions");
        assertThat(answer).hasStatusOk();
        return StreamSupport.stream(TestInstallation.body(answer).spliterator(), false).toList();
    }

    JsonNode subscription(int groupId) {
        return subscriptions().stream().filter(entry -> entry.get("groupId").asInt() == groupId).findFirst()
                .orElseThrow();
    }

    /** @return the commands the mock received that start with the given text, in order of arrival */
    List<String> commands(String commandStart) {
        return mockServer.commands().stream().filter(command -> command.startsWith(commandStart)).toList();
    }

    List<Long> locationNumbers(int groupId) {
        return database.select(ARTICLE_LOCATION.ARTICLE_NUMBER).from(ARTICLE_LOCATION)
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq(groupId))
                .orderBy(ARTICLE_LOCATION.ARTICLE_NUMBER)
                .fetch(ARTICLE_LOCATION.ARTICLE_NUMBER);
    }

    List<String> messageIds() {
        return database.select(ARTICLE.MESSAGE_ID).from(ARTICLE).orderBy(ARTICLE.MESSAGE_ID)
                .fetch(ARTICLE.MESSAGE_ID);
    }

    /** @return the ranges of the group as {@code "first-last"}, ordered by their start */
    List<String> ranges(int groupId) {
        return database.select(SYNCED_RANGE.FIRST_NUMBER, SYNCED_RANGE.LAST_NUMBER).from(SYNCED_RANGE)
                .where(SYNCED_RANGE.NEWSGROUP_ID.eq(groupId))
                .orderBy(SYNCED_RANGE.FIRST_NUMBER)
                .fetch(row -> row.value1() + "-" + row.value2());
    }
}
