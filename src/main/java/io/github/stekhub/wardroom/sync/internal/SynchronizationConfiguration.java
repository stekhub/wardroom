package io.github.stekhub.wardroom.sync.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches on the scheduling that the synchronization needs (spec 005). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SynchronizationProperties.class)
class SynchronizationConfiguration {
}
