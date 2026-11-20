package io.github.stekhub.wardroom.groups.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches on the scheduling that the group list refresh needs (ADR 0020). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(GroupListProperties.class)
class GroupListConfiguration {
}
