package io.github.stekhub.wardroom.sync.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param automatic whether groups are synchronized without a request of the owner: on the schedule and when a group
 *                  is subscribed (spec 005). The tests of other features switch this off, so that nothing talks to
 *                  their mock servers behind their back.
 */
@ConfigurationProperties("wardroom.synchronization")
record SynchronizationProperties(@DefaultValue("true") boolean automatic) {
}
