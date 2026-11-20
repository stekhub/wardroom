package io.github.stekhub.wardroom.server.internal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the key for the stored NNTP password when the application starts (spec 002, ADR 0025).
 *
 * <ul>
 * <li>Normally the key is the file {@code credentials.key} in the data directory. It is created when missing.</li>
 * <li>When the owner names a key file outside the data directory, only that file is used. It is never created.</li>
 * <li>A key file that exists but cannot be used stops the start. It is never overwritten, because it may be
 * recoverable.</li>
 * </ul>
 */
@Slf4j
final class CredentialKeyLoader {

    static final String KEY_FILE_NAME = "credentials.key";
    static final int KEY_LENGTH_BYTES = 32;

    private static final String KEY_ALGORITHM = "AES";
    private static final String OWNER_ONLY = "rw-------";

    private CredentialKeyLoader() {
    }

    /**
     * @param key       the key
     * @param generated whether the key was created by this start; passwords stored earlier cannot be read with it
     */
    record LoadedKey(SecretKey key, boolean generated) {
    }

    /**
     * @param dataDirectory   the data directory of the instance
     * @param externalKeyFile the key file outside the data directory, or {@code null} when none is configured
     * @throws CredentialKeyException when a key file exists but cannot be used, or the external one is missing
     */
    static LoadedKey load(Path dataDirectory, Path externalKeyFile) {
        Path internalKeyFile = dataDirectory.resolve(KEY_FILE_NAME);
        if (externalKeyFile != null) {
            if (!Files.exists(externalKeyFile)) {
                throw new CredentialKeyException("The key file " + externalKeyFile + " named by "
                        + "WARDROOM_ENCRYPTION_KEY_FILE does not exist. Wardroom does not create a key there.");
            }
            SecretKey key = read(externalKeyFile);
            if (Files.exists(internalKeyFile, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("The key file {} is no longer used, because WARDROOM_ENCRYPTION_KEY_FILE is set. It can be "
                        + "deleted once the setup works.", internalKeyFile);
            }
            return new LoadedKey(key, false);
        }
        if (Files.exists(internalKeyFile, LinkOption.NOFOLLOW_LINKS)) {
            return new LoadedKey(read(internalKeyFile), false);
        }
        return new LoadedKey(generate(internalKeyFile), true);
    }

    private static SecretKey read(Path keyFile) {
        byte[] keyBytes;
        try {
            keyBytes = Files.readAllBytes(keyFile);
        } catch (IOException e) {
            throw new CredentialKeyException("The key file " + keyFile + " cannot be read: " + e + ". The file was "
                    + "not changed.", e);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new CredentialKeyException("The key file " + keyFile + " has " + keyBytes.length + " bytes; a key "
                    + "has exactly " + KEY_LENGTH_BYTES + ". The file was not changed.");
        }
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    private static SecretKey generate(Path keyFile) {
        byte[] keyBytes = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(keyBytes);
        try {
            Files.createDirectories(keyFile.getParent());
            // The file is created with its final permissions, so the key is never readable by others.
            if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(keyFile,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
            } else {
                Files.createFile(keyFile);
            }
            Files.write(keyFile, keyBytes, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new CredentialKeyException("The key file " + keyFile + " appeared while Wardroom was starting. "
                    + "The file was not changed.", e);
        } catch (IOException e) {
            throw new CredentialKeyException("The key file " + keyFile + " cannot be created: " + e, e);
        }
        log.info("Created the key for the stored NNTP password in {}", keyFile);
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}
