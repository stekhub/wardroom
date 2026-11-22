package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.awaitRefreshEnd;
import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.body;
import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.refreshAndWait;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.jooq.tables.records.NewsgroupRecord;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.time.Duration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The activity of a group (spec 003): the baselines the group list refresh keeps, and the articles per day it
 * computes from them. Time passes in these tests by moving the stored baselines into the past.
 */
@IntegrationTest
class GroupActivityTest {

    private static final String GROUP = "example.talk";

    /** A refresh takes far less than this share of a day; the values are compared with this tolerance. */
    private static final double TOLERANCE = 0.01;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;
    long firstRefreshStart;

    /** Every test starts after the first load of the group list, in which the group had the high watermark 20. */
    @BeforeEach
    void startWithLoadedGroupList() throws IOException {
        browser = GroupListTestSupport.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        groupHasHighWatermark(20);
        serverId = GroupListTestSupport.addServer(browser, mockServer);
        firstRefreshStart = System.currentTimeMillis();
        refreshAndWait(browser, serverId);
    }

    @AfterEach
    void removeServer() throws IOException {
        awaitRefreshEnd(browser, serverId);
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    @Test
    void firstLoadStoresTheBaselinesAndGivesNoValue() {
        NewsgroupRecord group = group();

        assertThat(group.getActivityOlderAt()).isBetween(firstRefreshStart, System.currentTimeMillis());
        assertThat(group.getActivityYoungerAt()).isEqualTo(group.getActivityOlderAt());
        assertThat(group.getActivityOlderHighWatermark()).isEqualTo(20);
        assertThat(group.getActivityYoungerHighWatermark()).isEqualTo(20);
        assertThat(group.getArticlesPerDay()).isNull();
        JsonNode page = page();
        assertThat(page.get("activityKnown").asBoolean()).isFalse();
        assertThat(page.get("items").get(0).get("articlesPerDay").isNull()).isTrue();
    }

    @Test
    void activityIsTheGrowthSinceTheOlderBaselinePerDay() {
        baselines(Duration.ofDays(2), 20, Duration.ofDays(2), 20);
        NewsgroupRecord before = group();
        groupHasHighWatermark(120);

        refreshAndWait(browser, serverId);

        NewsgroupRecord group = group();
        assertThat(group.getArticlesPerDay()).isCloseTo(50.0, within(TOLERANCE));
        assertThat(group.getHighWatermark()).isEqualTo(120);
        assertThat(group.getActivityOlderAt()).as("the baselines stay").isEqualTo(before.getActivityOlderAt());
        assertThat(group.getActivityOlderHighWatermark()).isEqualTo(20);
        assertThat(group.getActivityYoungerAt()).isEqualTo(before.getActivityYoungerAt());
        assertThat(group.getActivityYoungerHighWatermark()).isEqualTo(20);
        JsonNode page = page();
        assertThat(page.get("activityKnown").asBoolean()).isTrue();
        assertThat(page.get("items").get(0).get("articlesPerDay").asDouble()).isCloseTo(50.0, within(TOLERANCE));
    }

    @Test
    void activityDoesNotDependOnHowOftenTheListIsRefreshed() {
        baselines(Duration.ofDays(2), 20, Duration.ofDays(2), 20);
        groupHasHighWatermark(70);
        refreshAndWait(browser, serverId);
        groupHasHighWatermark(120);

        refreshAndWait(browser, serverId);
        refreshAndWait(browser, serverId);

        assertThat(group().getArticlesPerDay()).isCloseTo(50.0, within(TOLERANCE));
    }

    @Test
    void groupWithoutNewArticlesHasTheActivityZero() {
        baselines(Duration.ofDays(2), 20, Duration.ofDays(2), 20);

        refreshAndWait(browser, serverId);

        assertThat(group().getArticlesPerDay()).isZero();
    }

    @Test
    void olderBaselineOfLessThanADayGivesNoValue() {
        baselines(Duration.ofHours(23), 20, Duration.ofHours(23), 20);
        groupHasHighWatermark(120);

        refreshAndWait(browser, serverId);

        assertThat(group().getArticlesPerDay()).isNull();
        assertThat(group().getActivityOlderHighWatermark()).as("the baseline stays").isEqualTo(20);
    }

    @Test
    void youngerBaselineOfLessThanSevenDaysStaysTheYoungerOne() {
        baselines(Duration.ofDays(10), 20, Duration.ofDays(6), 90);
        NewsgroupRecord before = group();
        groupHasHighWatermark(160);

        refreshAndWait(browser, serverId);

        NewsgroupRecord group = group();
        assertThat(group.getArticlesPerDay()).isCloseTo(14.0, within(TOLERANCE));
        assertThat(group.getActivityOlderAt()).isEqualTo(before.getActivityOlderAt());
        assertThat(group.getActivityYoungerAt()).isEqualTo(before.getActivityYoungerAt());
        assertThat(group.getActivityYoungerHighWatermark()).isEqualTo(90);
    }

    @Test
    void youngerBaselineOfSevenDaysBecomesTheOlderOne() {
        baselines(Duration.ofDays(10), 20, Duration.ofDays(7), 90);
        NewsgroupRecord before = group();
        groupHasHighWatermark(160);
        long refreshStart = System.currentTimeMillis();

        refreshAndWait(browser, serverId);

        NewsgroupRecord group = group();
        assertThat(group.getActivityOlderAt()).isEqualTo(before.getActivityYoungerAt());
        assertThat(group.getActivityOlderHighWatermark()).isEqualTo(90);
        assertThat(group.getActivityYoungerAt()).isBetween(refreshStart, System.currentTimeMillis());
        assertThat(group.getActivityYoungerHighWatermark()).isEqualTo(160);
        assertThat(group.getArticlesPerDay()).as("70 articles in the 7 days since the new older baseline")
                .isCloseTo(10.0, within(TOLERANCE));
    }

    @Test
    void afterATimeWithoutRefreshesTheActivityIsTheAverageOverThatTime() {
        baselines(Duration.ofDays(30), 20, Duration.ofDays(30), 20);
        groupHasHighWatermark(320);

        refreshAndWait(browser, serverId);

        NewsgroupRecord group = group();
        assertThat(group.getArticlesPerDay()).isCloseTo(10.0, within(TOLERANCE));
        assertThat(group.getActivityOlderHighWatermark()).isEqualTo(20);
        assertThat(group.getActivityYoungerHighWatermark()).isEqualTo(320);
    }

    @Test
    void highWatermarkBelowABaselineStartsBothBaselinesOver() {
        baselines(Duration.ofDays(10), 20, Duration.ofDays(3), 90);
        groupHasHighWatermark(160);
        refreshAndWait(browser, serverId);
        assertThat(group().getArticlesPerDay()).isNotNull();
        // Above the older baseline, below the younger one: the server numbered the group anew.
        groupHasHighWatermark(50);
        long refreshStart = System.currentTimeMillis();

        refreshAndWait(browser, serverId);

        NewsgroupRecord group = group();
        assertThat(group.getArticlesPerDay()).isNull();
        assertThat(group.getActivityOlderAt()).isBetween(refreshStart, System.currentTimeMillis());
        assertThat(group.getActivityYoungerAt()).isEqualTo(group.getActivityOlderAt());
        assertThat(group.getActivityOlderHighWatermark()).isEqualTo(50);
        assertThat(group.getActivityYoungerHighWatermark()).isEqualTo(50);
    }

    @Test
    void highWatermarkThatASynchronizationMovedDoesNotChangeTheActivity() {
        baselines(Duration.ofDays(2), 20, Duration.ofDays(2), 20);
        // The synchronization of a subscribed group stores the high watermark it saw.
        database.update(NEWSGROUP).set(NEWSGROUP.HIGH_WATERMARK, 115L).where(NEWSGROUP.NAME.eq(GROUP)).execute();
        groupHasHighWatermark(120);

        refreshAndWait(browser, serverId);

        assertThat(group().getArticlesPerDay()).isCloseTo(50.0, within(TOLERANCE));
    }

    @Test
    void groupThatAppearsLaterStartsWithItsOwnBaselines() {
        baselines(Duration.ofDays(2), 20, Duration.ofDays(2), 20);
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", GROUP + " 120 1 y", "example.new 7 1 y", ".");
        long refreshStart = System.currentTimeMillis();

        refreshAndWait(browser, serverId);

        NewsgroupRecord newGroup = database.selectFrom(NEWSGROUP).where(NEWSGROUP.NAME.eq("example.new"))
                .fetchSingle();
        assertThat(newGroup.getActivityOlderAt()).isBetween(refreshStart, System.currentTimeMillis());
        assertThat(newGroup.getActivityOlderHighWatermark()).isEqualTo(7);
        assertThat(newGroup.getArticlesPerDay()).isNull();
        assertThat(group().getArticlesPerDay()).isCloseTo(50.0, within(TOLERANCE));
    }

    /** The next refresh reads this high watermark for the group. */
    private void groupHasHighWatermark(long highWatermark) {
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", GROUP + " " + highWatermark + " 1 y", ".");
    }

    /** Stores the baselines of the group as if they had been taken this long ago. */
    private void baselines(Duration olderAge, long olderHighWatermark, Duration youngerAge,
            long youngerHighWatermark) {
        long now = System.currentTimeMillis();
        database.update(NEWSGROUP)
                .set(NEWSGROUP.ACTIVITY_OLDER_AT, now - olderAge.toMillis())
                .set(NEWSGROUP.ACTIVITY_OLDER_HIGH_WATERMARK, olderHighWatermark)
                .set(NEWSGROUP.ACTIVITY_YOUNGER_AT, now - youngerAge.toMillis())
                .set(NEWSGROUP.ACTIVITY_YOUNGER_HIGH_WATERMARK, youngerHighWatermark)
                .where(NEWSGROUP.NAME.eq(GROUP))
                .execute();
    }

    private NewsgroupRecord group() {
        return database.selectFrom(NEWSGROUP).where(NEWSGROUP.NAME.eq(GROUP)).fetchSingle();
    }

    private JsonNode page() {
        return body(browser.get("/api/servers/" + serverId + "/groups"));
    }
}
