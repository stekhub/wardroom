package io.github.stekhub.wardroom.auth.internal;

import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.WardroomApplication;
import io.github.stekhub.wardroom.platform.SecretCipher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the application on a data directory whose key file was lost while a second factor is stored (spec 012). */
@ExtendWith(OutputCaptureExtension.class)
class TwoFactorKeyStartupTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String WARNING = "The secret of two-factor authentication cannot be read with it";

    @Test
    void lostKeyFileIsReportedWhenASecretIsStored(@TempDir Path dataDirectory, CapturedOutput output)
            throws IOException {
        try (ConfigurableApplicationContext application = start(dataDirectory)) {
            byte[] secretEncrypted = application.getBean(SecretCipher.class)
                    .encrypt(TwoFactorService.SECRET_PURPOSE, SECRET);
            application.getBean(DSLContext.class).insertInto(WARDROOM_USER)
                    .set(WARDROOM_USER.USERNAME, "owner")
                    .set(WARDROOM_USER.PASSWORD_HASH, "not a hash")
                    .set(WARDROOM_USER.TOTP_SECRET, secretEncrypted)
                    .set(WARDROOM_USER.TOTP_ENABLED, true)
                    .execute();
        }
        assertThat(output.getOut()).doesNotContain(WARNING);

        Files.delete(dataDirectory.resolve("credentials.key"));
        try (ConfigurableApplicationContext application = start(dataDirectory)) {
            assertThat(application.getBean(SecretCipher.class).keyWasGenerated()).isTrue();
        }

        assertThat(output.getOut()).contains("WARN").contains(WARNING).contains("recovery code")
                .contains("reset the password");
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void newKeyFileOfAFreshInstallationIsNotReported(@TempDir Path dataDirectory, CapturedOutput output) {
        try (ConfigurableApplicationContext application = start(dataDirectory)) {
            assertThat(application.getBean(SecretCipher.class).keyWasGenerated()).isTrue();
        }

        assertThat(output.getOut()).doesNotContain(WARNING);
    }

    private static ConfigurableApplicationContext start(Path dataDirectory) {
        return new SpringApplicationBuilder(WardroomApplication.class)
                .profiles("test")
                .run("--wardroom.data-dir=" + dataDirectory, "--server.port=0");
    }
}
