package io.github.stekhub.wardroom;

import io.github.stekhub.wardroom.platform.LogLevels;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WardroomApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WardroomApplication.class);
        application.setDefaultProperties(LogLevels.fromEnvironmentValue(System.getenv(LogLevels.ENVIRONMENT_VARIABLE)));
        application.run(args);
    }
}
