package io.github.stekhub.wardroom.settings.internal;

import static io.github.stekhub.wardroom.jooq.Tables.APP_SETTING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.settings.Settings;
import io.github.stekhub.wardroom.settings.SynchronizationSettings;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The settings endpoints of spec 005, against the whole application and a real SQLite database. */
@IntegrationTest
class SettingsTest {

    private static final String DEFAULTS =
            "{\"syncIntervalMinutes\": 15, \"initialDepth\": 500, \"groupListIntervalHours\": 24}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    Settings settings;

    TestBrowser browser;

    @BeforeEach
    void startWithOwner() {
        browser = TestInstallation.loggedInOwner(mvc, database);
    }

    @Test
    void endpointsNeedASessionAndTheCsrfToken() {
        assertThat(new TestBrowser(mvc).get("/api/settings")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).put("/api/settings", DEFAULTS)).hasStatus(HttpStatus.UNAUTHORIZED);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        assertThat(secondBrowser.post("/api/auth/login",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(secondBrowser.withoutCsrfToken().put("/api/settings", body(5, 50, 1)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(settings.synchronization()).isEqualTo(SynchronizationSettings.DEFAULTS);
    }

    @Test
    void anInstallationInWhichNothingWasChangedHasTheDefaults() {
        assertThat(browser.get("/api/settings")).hasStatusOk().bodyJson().isStrictlyEqualTo(DEFAULTS);
    }

    @Test
    void savedSettingsAreReturnedAndUsed() {
        assertThat(browser.put("/api/settings", body(30, 2000, 12))).hasStatusOk().bodyJson()
                .isStrictlyEqualTo(body(30, 2000, 12));

        assertThat(browser.get("/api/settings")).hasStatusOk().bodyJson().isStrictlyEqualTo(body(30, 2000, 12));
        assertThat(settings.synchronization()).isEqualTo(new SynchronizationSettings(30, 2000, 12));
        assertThat(database.select(APP_SETTING.KEY, APP_SETTING.VALUE).from(APP_SETTING)
                .fetchMap(APP_SETTING.KEY, APP_SETTING.VALUE))
                .containsEntry("sync.interval_minutes", "30")
                .containsEntry("sync.initial_depth", "2000")
                .containsEntry("sync.group_list_interval_hours", "12");
    }

    @Test
    void theEndsOfTheRangesAreAccepted() {
        assertThat(browser.put("/api/settings", body(5, 50, 1))).hasStatusOk();
        assertThat(browser.put("/api/settings", body(1440, 10_000, 168))).hasStatusOk();

        assertThat(settings.synchronization()).isEqualTo(new SynchronizationSettings(1440, 10_000, 168));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "4    | 500   | 24  | syncIntervalMinutes    | The synchronization interval must be between 5 and 1440 minutes.",
        "1441 | 500   | 24  | syncIntervalMinutes    | The synchronization interval must be between 5 and 1440 minutes.",
        "15   | 49    | 24  | initialDepth           | The first synchronization depth must be between 50 and 10000 articles.",
        "15   | 10001 | 24  | initialDepth           | The first synchronization depth must be between 50 and 10000 articles.",
        "15   | 500   | 0   | groupListIntervalHours | The group list refresh interval must be between 1 and 168 hours.",
        "15   | 500   | 169 | groupListIntervalHours | The group list refresh interval must be between 1 and 168 hours."})
    void aValueOutsideItsRangeIsRejectedAndNothingIsStored(int syncIntervalMinutes, int initialDepth,
            int groupListIntervalHours, String field, String rule) {
        assertThat(browser.put("/api/settings", body(syncIntervalMinutes, initialDepth, groupListIntervalHours)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).isEqualTo(rule))
                .hasPathSatisfying("$.errors[0].field", failedField -> assertThat(failedField).isEqualTo(field));

        assertThat(settings.synchronization()).isEqualTo(SynchronizationSettings.DEFAULTS);
    }

    @Test
    void aMissingValueIsRejected() {
        assertThat(browser.put("/api/settings", "{\"syncIntervalMinutes\": 15, \"initialDepth\": 500}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("groupListIntervalHours");
    }

    @Test
    void aStoredValueThatIsNoNumberOrOutsideItsRangeCountsAsTheDefault() {
        database.insertInto(APP_SETTING, APP_SETTING.KEY, APP_SETTING.VALUE)
                .values("sync.interval_minutes", "often")
                .values("sync.initial_depth", "7")
                .values("sync.group_list_interval_hours", "48")
                .execute();

        assertThat(settings.synchronization()).isEqualTo(new SynchronizationSettings(15, 500, 48));
    }

    private static String body(int syncIntervalMinutes, int initialDepth, int groupListIntervalHours) {
        return "{\"syncIntervalMinutes\": %d, \"initialDepth\": %d, \"groupListIntervalHours\": %d}"
                .formatted(syncIntervalMinutes, initialDepth, groupListIntervalHours);
    }
}
