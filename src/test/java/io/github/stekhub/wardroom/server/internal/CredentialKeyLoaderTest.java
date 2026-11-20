package io.github.stekhub.wardroom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stekhub.wardroom.server.internal.CredentialKeyLoader.LoadedKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The key file rules of spec 002 and ADR 0025. */
@ExtendWith(OutputCaptureExtension.class)
class CredentialKeyLoaderTest {

    @TempDir
    Path temporaryDirectory;

    Path dataDirectory;
    Path internalKeyFile;
    Path externalKeyFile;

    @BeforeEach
    void setUpDirectories() throws IOException {
        dataDirectory = Files.createDirectory(temporaryDirectory.resolve("config"));
        internalKeyFile = dataDirectory.resolve("credentials.key");
        externalKeyFile = Files.createDirectory(temporaryDirectory.resolve("secrets")).resolve("wardroom_key");
    }

    @Test
    void missingKeyFileIsCreatedWith32RandomBytes() throws IOException {
        LoadedKey loadedKey = CredentialKeyLoader.load(dataDirectory, null);

        assertThat(loadedKey.generated()).isTrue();
        byte[] written = Files.readAllBytes(internalKeyFile);
        assertThat(written).hasSize(32).isEqualTo(loadedKey.key().getEncoded()).isNotEqualTo(new byte[32]);
        if (internalKeyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(internalKeyFile)))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void existingKeyFileIsReadAndNotChanged() throws IOException {
        byte[] keyBytes = keyBytes(7);
        Files.write(internalKeyFile, keyBytes);

        LoadedKey loadedKey = CredentialKeyLoader.load(dataDirectory, null);

        assertThat(loadedKey.generated()).isFalse();
        assertThat(loadedKey.key().getEncoded()).isEqualTo(keyBytes);
        assertThat(Files.readAllBytes(internalKeyFile)).isEqualTo(keyBytes);
    }

    @Test
    void keyFileOfTheWrongLengthStopsTheStartAndIsNotChanged() throws IOException {
        byte[] tooShort = Arrays.copyOf(keyBytes(7), 31);
        Files.write(internalKeyFile, tooShort);

        assertThatThrownBy(() -> CredentialKeyLoader.load(dataDirectory, null))
                .isInstanceOf(CredentialKeyException.class)
                .hasMessageContaining(internalKeyFile.toString())
                .hasMessageContaining("31 bytes");
        assertThat(Files.readAllBytes(internalKeyFile)).isEqualTo(tooShort);
    }

    @Test
    void unreadableKeyFileStopsTheStartAndIsNotChanged() throws IOException {
        // A directory in place of the file cannot be read as a key on any operating system.
        Files.createDirectory(internalKeyFile);

        assertThatThrownBy(() -> CredentialKeyLoader.load(dataDirectory, null))
                .isInstanceOf(CredentialKeyException.class)
                .hasMessageContaining(internalKeyFile.toString())
                .hasMessageContaining("cannot be read");
        assertThat(internalKeyFile).isDirectory();
    }

    @Test
    void externalKeyFileIsUsedAndNothingIsWrittenToTheDataDirectory() throws IOException {
        byte[] keyBytes = keyBytes(9);
        Files.write(externalKeyFile, keyBytes);

        LoadedKey loadedKey = CredentialKeyLoader.load(dataDirectory, externalKeyFile);

        assertThat(loadedKey.generated()).isFalse();
        assertThat(loadedKey.key().getEncoded()).isEqualTo(keyBytes);
        assertThat(dataDirectory).isEmptyDirectory();
    }

    @Test
    void missingExternalKeyFileStopsTheStartAndNoKeyIsGenerated() {
        assertThatThrownBy(() -> CredentialKeyLoader.load(dataDirectory, externalKeyFile))
                .isInstanceOf(CredentialKeyException.class)
                .hasMessageContaining(externalKeyFile.toString())
                .hasMessageContaining("WARDROOM_ENCRYPTION_KEY_FILE");
        assertThat(externalKeyFile).doesNotExist();
        assertThat(dataDirectory).isEmptyDirectory();
    }

    @Test
    void externalKeyFileOfTheWrongLengthStopsTheStart() throws IOException {
        byte[] tooLong = new byte[33];
        Files.write(externalKeyFile, tooLong);

        assertThatThrownBy(() -> CredentialKeyLoader.load(dataDirectory, externalKeyFile))
                .isInstanceOf(CredentialKeyException.class)
                .hasMessageContaining(externalKeyFile.toString())
                .hasMessageContaining("33 bytes");
        assertThat(Files.readAllBytes(externalKeyFile)).isEqualTo(tooLong);
        assertThat(dataDirectory).isEmptyDirectory();
    }

    @Test
    void leftoverKeyFileInTheDataDirectoryIsIgnoredWithAWarning(CapturedOutput output) throws IOException {
        byte[] leftover = keyBytes(1);
        byte[] external = keyBytes(2);
        Files.write(internalKeyFile, leftover);
        Files.write(externalKeyFile, external);

        LoadedKey loadedKey = CredentialKeyLoader.load(dataDirectory, externalKeyFile);

        assertThat(loadedKey.key().getEncoded()).isEqualTo(external);
        assertThat(Files.readAllBytes(internalKeyFile)).isEqualTo(leftover);
        assertThat(output).contains("WARN").contains(internalKeyFile.toString()).contains("is no longer used")
                .contains("can be deleted");
    }

    private static byte[] keyBytes(int fill) {
        byte[] keyBytes = new byte[32];
        Arrays.fill(keyBytes, (byte) fill);
        return keyBytes;
    }
}
