package io.github.stekhub.wardroom.posting.internal;

import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.posting.internal.PostingFailures.ServerNotFoundException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The posting identity of a server: the name, the email address and the signature the owner posts with (spec 009,
 * "Posting identity").
 */
@Service
@RequiredArgsConstructor
class PostingIdentityService {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_EMAIL_LENGTH = 254;
    static final int MAX_SIGNATURE_LENGTH = 2_000;

    /** {@code local@domain}. The domain is also used in Message-IDs, so it is held to letters, digits and hyphens. */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*");

    private final PostingRepository postingRepository;

    /**
     * @param name      {@code null} until the owner has set an identity
     * @param email     {@code null} until the owner has set an identity
     * @param signature {@code null} when the owner has none
     */
    record Identity(String name, String email, String signature) {

        boolean isSet() {
            return name != null && email != null;
        }
    }

    /**
     * @return the identity; its fields are {@code null} when none was set
     * @throws ServerNotFoundException when no server has this ID
     */
    Identity identity(long serverId) {
        return postingRepository.findIdentity(serverId).orElseThrow(ServerNotFoundException::new);
    }

    /**
     * Stores the identity. Line breaks and other control characters are removed from the name and the email
     * address, because both are used in headers; the signature keeps its line breaks.
     *
     * @return the identity as it is stored
     * @throws ServerNotFoundException  when no server has this ID
     * @throws FieldValidationException when the name is missing or the email address has not the form
     *                                  {@code local@domain}
     */
    Identity store(long serverId, String name, String email, String signature) {
        String storedName = ArticleComposer.cleaned(name == null ? "" : name);
        if (storedName.isEmpty()) {
            throw new FieldValidationException("name", "Enter the name your posts are sent with.");
        }
        if (storedName.length() > MAX_NAME_LENGTH) {
            throw new FieldValidationException("name",
                    "The name can have at most " + MAX_NAME_LENGTH + " characters.");
        }
        String storedEmail = ArticleComposer.cleaned(email == null ? "" : email);
        if (storedEmail.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(storedEmail).matches()) {
            throw new FieldValidationException("email", "Enter an email address of the form name@example.org.");
        }
        String storedSignature = signatureText(signature);
        if (storedSignature != null && storedSignature.length() > MAX_SIGNATURE_LENGTH) {
            throw new FieldValidationException("signature",
                    "The signature can have at most " + MAX_SIGNATURE_LENGTH + " characters.");
        }
        Identity identity = new Identity(storedName, storedEmail, storedSignature);
        if (!postingRepository.storeIdentity(serverId, identity)) {
            throw new ServerNotFoundException();
        }
        return identity;
    }

    /** The signature with line feeds as line endings and without other control characters; {@code null} if blank. */
    private static String signatureText(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        StringBuilder text = new StringBuilder(signature.length());
        signature.replace("\r\n", "\n").replace('\r', '\n').codePoints()
                .filter(codePoint -> codePoint == '\n' || !Character.isISOControl(codePoint))
                .forEach(text::appendCodePoint);
        return text.toString().stripTrailing();
    }
}
