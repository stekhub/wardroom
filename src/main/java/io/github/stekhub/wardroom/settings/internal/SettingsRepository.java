package io.github.stekhub.wardroom.settings.internal;

import static io.github.stekhub.wardroom.jooq.Tables.APP_SETTING;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Database access for the instance settings (data model: {@code app_setting}). */
@Repository
@RequiredArgsConstructor
class SettingsRepository {

    private final DSLContext database;

    /** @return every stored setting by its key */
    Map<String, String> findAll() {
        return database.select(APP_SETTING.KEY, APP_SETTING.VALUE).from(APP_SETTING)
                .fetchMap(APP_SETTING.KEY, APP_SETTING.VALUE);
    }

    void store(String key, String value) {
        database.insertInto(APP_SETTING, APP_SETTING.KEY, APP_SETTING.VALUE)
                .values(key, value)
                .onConflict(APP_SETTING.KEY)
                .doUpdate()
                .set(APP_SETTING.VALUE, value)
                .execute();
    }
}
