package io.github.stekhub.wardroom.groups.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param automaticRefresh whether the group list is fetched without a request of the owner: when a server is
 *                         created and on the schedule (spec 003). The tests of other features switch this off, so
 *                         that nothing talks to their mock servers behind their back.
 */
@ConfigurationProperties("wardroom.group-list")
record GroupListProperties(@DefaultValue("true") boolean automaticRefresh) {
}
