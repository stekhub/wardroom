package io.github.stekhub.wardroom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stekhub.wardroom.WardroomApplication;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.platform.SecretCipher;
import io.github.stekhub.wardroom.platform.SecretKeyException;
import io.github.stekhub.wardroom.server.NntpServers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the application on a data directory to show what happens to the key for the stored NNTP password at start
 * (spec 002, ADR 0025).
 */
@ExtendWith(OutputCaptureExtension.class)
class ServerStartupTest {

    private static final String NNTP_PASSWORD = "s3cret-Passw0rd";

    @TempDir
    Path temporaryDirectory;

    Path dataDirectory;
    Path internalKeyFile;
    Path externalKeyFile;

    @BeforeEach
    void setUpDirectories() throws IOException {
        dataDirectory = temporaryDirectory.resolve("config");
        internalKeyFile = dataDirectory.resolve("credentials.key");
        externalKeyFile = Files.createDirectory(temporaryDirectory.resolve("secrets")).resolve("wardroom_key");
    }

    @Test
    void storedPasswordSurvivesARestartAndIsMarkedInvalidWhenTheKeyFileIsLost(CapturedOutput output)
            throws IOException {
        long serverId;
        try (ConfigurableApplicationContext application = start()) {
            serverId = storeServerWithPassword(application);
            assertThat(Files.readAllBytes(internalKeyFile)).hasSize(32);
        }

        try (ConfigurableApplicationContext application = start()) {
            assertThat(application.getBean(NntpServers.class).connectionSettings(serverId).orElseThrow().password())
                    .isEqualTo(NNTP_PASSWORD);
            assertThat(storedServer(application).credentialsInvalid()).isFalse();
        }

        Files.delete(internalKeyFile);
        try (ConfigurableApplicationContext application = start()) {
            assertThat(storedServer(application).credentialsInvalid()).isTrue();
            assertThat(storedServer(application).host()).as("the server itself is kept").isEqualTo("news.example.com");
            assertThat(Files.readAllBytes(internalKeyFile)).as("a new key was created").hasSize(32);
        }
        assertThat(output).contains("WARN").contains("The key file was missing and a new key was created");
        assertThat(output.getAll()).doesNotContain(NNTP_PASSWORD);
    }

    @Test
    void keyFileOfTheWrongLengthStopsTheStartAndIsNotChanged() throws IOException {
        Files.createDirectories(dataDirectory);
        byte[] tooShort = new byte[31];
        Files.write(internalKeyFile, tooShort);

        assertThatThrownBy(this::start)
                .rootCause()
                .isInstanceOf(SecretKeyException.class)
                .hasMessageContaining(internalKeyFile.toString());
        assertThat(Files.readAllBytes(internalKeyFile)).isEqualTo(tooShort);
    }

    @Test
    void externalKeyFileIsUsedAndNothingIsWrittenToTheDataDirectory() throws IOException {
        byte[] externalKey = new byte[32];
        Arrays.fill(externalKey, (byte) 5);
        Files.write(externalKeyFile, externalKey);

        long serverId;
        try (ConfigurableApplicationContext application = startWithExternalKeyFile()) {
            serverId = storeServerWithPassword(application);
        }
        try (ConfigurableApplicationContext application = startWithExternalKeyFile()) {
            assertThat(application.getBean(NntpServers.class).connectionSettings(serverId).orElseThrow().password())
                    .isEqualTo(NNTP_PASSWORD);
        }

        assertThat(internalKeyFile).doesNotExist();
        assertThat(Files.readAllBytes(externalKeyFile)).isEqualTo(externalKey);
    }

    @Test
    void missingExternalKeyFileStopsTheStartAndNoKeyIsGenerated() {
        assertThatThrownBy(this::startWithExternalKeyFile)
                .rootCause()
                .isInstanceOf(SecretKeyException.class)
                .hasMessageContaining(externalKeyFile.toString());
        assertThat(externalKeyFile).doesNotExist();
        assertThat(internalKeyFile).doesNotExist();
    }

    /** Spec 012: a password that was stored before the update, in format version 1, stays readable. */
    @Test
    void passwordStoredBeforePurposesExistedIsStillRead() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 9);
        Files.createDirectories(dataDirectory);
        Files.write(internalKeyFile, key);
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 3);
        Cipher aesGcm = Cipher.getInstance("AES/GCM/NoPadding");
        aesGcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertextAndTag = aesGcm.doFinal(NNTP_PASSWORD.getBytes(StandardCharsets.UTF_8));
        byte[] storedInVersion1 = new byte[1 + nonce.length + ciphertextAndTag.length];
        storedInVersion1[0] = 1;
        System.arraycopy(nonce, 0, storedInVersion1, 1, nonce.length);
        System.arraycopy(ciphertextAndTag, 0, storedInVersion1, 1 + nonce.length, ciphertextAndTag.length);

        try (ConfigurableApplicationContext application = start()) {
            long serverId = application.getBean(ServerRepository.class).insert(new StoredServer(0, "My provider",
                    "news.example.com", 563, NntpTlsMode.IMPLICIT, AuthMethod.USER_PASS, "reader", storedInVersion1,
                    4, false, null));

            assertThat(application.getBean(NntpServers.class).connectionSettings(serverId).orElseThrow().password())
                    .isEqualTo(NNTP_PASSWORD);
            assertThat(storedServer(application).credentialsInvalid()).isFalse();
        }
    }

    @Test
    void leftoverKeyFileIsIgnoredWithAWarningWhenAnExternalOneIsConfigured(CapturedOutput output) throws IOException {
        byte[] leftoverKey = new byte[32];
        Files.createDirectories(dataDirectory);
        Files.write(internalKeyFile, leftoverKey);
        byte[] externalKey = new byte[32];
        Arrays.fill(externalKey, (byte) 5);
        Files.write(externalKeyFile, externalKey);

        try (ConfigurableApplicationContext application = startWithExternalKeyFile()) {
            assertThat(application.isRunning()).isTrue();
        }

        assertThat(Files.readAllBytes(internalKeyFile)).isEqualTo(leftoverKey);
        assertThat(output).contains("WARN").contains("is no longer used").contains("can be deleted");
    }

    private ConfigurableApplicationContext start(String... furtherArguments) {
        String[] arguments = Arrays.copyOf(new String[] {"--wardroom.data-dir=" + dataDirectory, "--server.port=0"},
                2 + furtherArguments.length);
        System.arraycopy(furtherArguments, 0, arguments, 2, furtherArguments.length);
        return new SpringApplicationBuilder(WardroomApplication.class).profiles("test").run(arguments);
    }

    /** The command line argument stands in for the environment variable of the same name. */
    private ConfigurableApplicationContext startWithExternalKeyFile() {
        return start("--WARDROOM_ENCRYPTION_KEY_FILE=" + externalKeyFile);
    }

    private static long storeServerWithPassword(ConfigurableApplicationContext application) {
        byte[] passwordEncrypted = application.getBean(SecretCipher.class)
                .encrypt(SecretCipher.PURPOSE_OF_VERSION_1, NNTP_PASSWORD);
        return application.getBean(ServerRepository.class).insert(new StoredServer(0, "My provider",
                "news.example.com", 563, NntpTlsMode.IMPLICIT, AuthMethod.USER_PASS, "reader", passwordEncrypted, 4,
                false, null));
    }

    private static StoredServer storedServer(ConfigurableApplicationContext application) {
        return application.getBean(ServerRepository.class).findAll().getFirst();
    }
}
