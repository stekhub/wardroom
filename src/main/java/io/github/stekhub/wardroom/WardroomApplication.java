package io.github.stekhub.wardroom;

import io.github.stekhub.wardroom.platform.LogLevels;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Wardroom checks logins itself (spec 001). Without the exclusion, Spring Boot would create a second, unused user
// with a generated password and print that password in the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class WardroomApplication {

    public static void main(String[] args) {
        // jOOQ prints a logo and a tip of the day into the log on first use unless it is told not to.
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
        SpringApplication application = new SpringApplication(WardroomApplication.class);
        application.setDefaultProperties(LogLevels.fromEnvironmentValue(System.getenv(LogLevels.ENVIRONMENT_VARIABLE)));
        application.run(args);
    }
}
