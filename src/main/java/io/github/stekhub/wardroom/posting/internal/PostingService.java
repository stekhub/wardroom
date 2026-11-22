package io.github.stekhub.wardroom.posting.internal;

import io.github.stekhub.wardroom.articles.Threads;
import io.github.stekhub.wardroom.groups.GroupSynchronization;
import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpConnector;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpPostResult;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.posting.internal.ArticleComposer.Composed;
import io.github.stekhub.wardroom.posting.internal.ArticleComposer.Parts;
import io.github.stekhub.wardroom.posting.internal.PostingFailures.FailureType;
import io.github.stekhub.wardroom.posting.internal.PostingFailures.GroupNotFoundException;
import io.github.stekhub.wardroom.posting.internal.PostingFailures.ParentNotFoundException;
import io.github.stekhub.wardroom.posting.internal.PostingFailures.PostingFailedException;
import io.github.stekhub.wardroom.posting.internal.PostingFailures.PostingNotPossibleException;
import io.github.stekhub.wardroom.posting.internal.PostingIdentityService.Identity;
import io.github.stekhub.wardroom.posting.internal.PostingRepository.Parent;
import io.github.stekhub.wardroom.posting.internal.PostingRepository.StoredArticle;
import io.github.stekhub.wardroom.server.NntpServers;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Posts an article of the owner: a new discussion or a reply (spec 009).
 *
 * <ul>
 * <li>The article is built here, from the draft, the posting identity and the stored headers of the parent.</li>
 * <li>It is sent on the connection that is kept for interactive actions, once. A server with a separate posting
 * server (spec 002) gets a connection of its own to that server for each post. Nothing is sent again on its own;
 * the owner tries again with the same Message-ID, so a second attempt cannot create a second article.</li>
 * <li>An article the server accepted is stored at once, threaded and read. Then its group is synchronized.</li>
 * <li>The text of an article is never logged.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PostingService {

    /** The largest article that is sent, headers included (spec 009, "Checks before sending"). */
    static final int MAX_ARTICLE_BYTES = 64 * 1024;

    private static final String SIGNATURE_SEPARATOR = "-- ";
    private static final String CANNOT_LOG_IN = "Wardroom cannot log in to the server. Enter the username and "
            + "password again in the server settings.";
    private static final String CANNOT_LOG_IN_TO_POSTING_SERVER = "Wardroom cannot log in to the posting server. "
            + "Check its login in the server settings.";

    private final PostingRepository postingRepository;
    private final SubscribedGroups subscribedGroups;
    private final NntpServers nntpServers;
    private final NntpConnections connections;
    private final NntpConnector connector;
    private final Threads threads;
    private final GroupSynchronization groupSynchronization;
    private final TransactionOperations transactionOperations;
    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * What the owner wrote.
     *
     * @param parentId         the article that is answered, or {@code null} for a new discussion
     * @param includeSignature whether the signature of the identity is appended
     * @param messageId        {@code null} at the first attempt; afterwards the Message-ID the failed attempt
     *                         returned
     */
    record Draft(Long parentId, String subject, String body, boolean includeSignature, String messageId) {
    }

    /** The article that was posted. */
    record Posted(long articleId, long rootId, String messageId) {
    }

    /**
     * @throws GroupNotFoundException      when the group is unknown or not subscribed
     * @throws ParentNotFoundException     when no article has the ID of the parent
     * @throws FieldValidationException    when the subject or the text is missing, the article is too large, or
     *                                     the Message-ID is not one of Wardroom
     * @throws PostingNotPossibleException when there is no identity, the group does not allow posting, the author
     *                                     of the parent asked for replies by email, or the parent has no content
     * @throws PostingFailedException      when the server did not take the article
     */
    Posted post(long groupId, Draft draft) {
        SubscribedGroup group = subscribedGroups.find(groupId).orElseThrow(GroupNotFoundException::new);
        if (!postingRepository.postingAllowed(groupId)) {
            throw new PostingNotPossibleException("This group does not allow posting.");
        }
        Identity identity = postingRepository.findIdentity(group.serverId())
                .orElseThrow(GroupNotFoundException::new);
        if (!identity.isSet()) {
            throw new PostingNotPossibleException("Set your posting identity before you post.");
        }

        List<String> newsgroups = List.of(group.name());
        String references = null;
        if (draft.parentId() != null) {
            Parent parent = postingRepository.findParent(draft.parentId())
                    .orElseThrow(ParentNotFoundException::new);
            if (parent.stub() || parent.rawHeaders() == null) {
                throw new PostingNotPossibleException("The article you reply to has not been loaded.");
            }
            String followupTo = HeaderBlock.value(parent.rawHeaders(), "Followup-To");
            if (ArticleComposer.repliesByEmailOnly(followupTo)) {
                throw new PostingNotPossibleException(
                        "The author of this article asked for replies by email. Wardroom does not send email.");
            }
            List<String> targetGroups = ArticleComposer.targetGroups(followupTo,
                    HeaderBlock.value(parent.rawHeaders(), "Newsgroups"));
            // A parent that names no valid group is answered where it is read.
            if (!targetGroups.isEmpty()) {
                newsgroups = targetGroups;
            }
            references = ArticleComposer.references(parent.references(), parent.messageId());
        }

        String subject = ArticleComposer.cleaned(draft.subject() == null ? "" : draft.subject());
        if (subject.isEmpty()) {
            throw new FieldValidationException("subject", "Enter a subject.");
        }
        if (draft.body() == null || draft.body().isBlank()) {
            throw new FieldValidationException("body", "Enter a text.");
        }
        String messageId = draft.messageId();
        if (messageId == null) {
            messageId = ArticleComposer.newMessageId(identity.email());
        } else if (!ArticleComposer.isOwnMessageId(messageId, identity.email())) {
            throw new FieldValidationException("messageId", "This is not a Message-ID of this server's identity.");
        }
        boolean retry = draft.messageId() != null;
        if (retry) {
            // An earlier attempt was stored; the answer to it did not reach the browser.
            Optional<StoredArticle> stored = postingRepository.findArticle(messageId);
            if (stored.isPresent()) {
                return new Posted(stored.get().articleId(), stored.get().threadRootId(), messageId);
            }
        }

        Instant now = Instant.now();
        Composed composed = ArticleComposer.compose(new Parts(identity.name(), identity.email(), newsgroups, subject,
                messageId, now, references, bodyOf(draft, identity), applicationVersion()));
        if (composed.bytes() > MAX_ARTICLE_BYTES) {
            throw new FieldValidationException("body", "The article is larger than 64 KB. Shorten the text.");
        }

        send(group, composed, messageId, retry);

        String storedMessageId = messageId;
        String storedReferences = references;
        long articleId = transactionOperations.execute(status -> postingRepository.storePostedArticle(groupId,
                storedMessageId, composed, storedReferences, now.toEpochMilli()));
        threads.threadArticle(articleId);
        log.info("Article {} was posted from group {} ({} bytes)", articleId, groupId, composed.bytes());
        // The synchronization brings the article with its number in the group, when the server lists it.
        groupSynchronization.synchronizeSoon(groupId);
        return new Posted(articleId, postingRepository.findThreadRootId(articleId), messageId);
    }

    private void send(SubscribedGroup group, Composed composed, String messageId, boolean retry) {
        long serverId = group.serverId();
        Optional<NntpServerSettings> postingServerSettings;
        try {
            postingServerSettings = nntpServers.postingServerSettings(serverId);
        } catch (NntpException e) {
            // A stored password can no longer be read; the message says which one.
            throw new PostingFailedException(FailureType.AUTH_REQUIRED, e.getMessage(), messageId);
        }
        boolean separatePostingServer = postingServerSettings.isPresent();
        NntpPostResult result;
        try {
            if (separatePostingServer) {
                try (NntpClient client = connector.connect(postingServerSettings.get())) {
                    result = client.post(composed.text());
                }
            } else {
                if (nntpServers.credentialsInvalid(serverId)) {
                    throw new PostingFailedException(FailureType.AUTH_REQUIRED, CANNOT_LOG_IN, messageId);
                }
                NntpServerSettings serverSettings = nntpServers.connectionSettings(serverId)
                        .orElseThrow(GroupNotFoundException::new);
                result = connections.interactiveWithoutRetry(serverSettings,
                        client -> client.post(composed.text()));
            }
        } catch (NntpException e) {
            log.warn("Posting from group {} failed: {} ({})", group.groupId(), e.type(), e.getMessage());
            if (e.type() == NntpFailureType.AUTH_FAILED && separatePostingServer) {
                // Reading is not affected; the login of the server keeps its state.
                throw new PostingFailedException(FailureType.AUTH_REQUIRED, CANNOT_LOG_IN_TO_POSTING_SERVER,
                        messageId);
            }
            if (e.type() == NntpFailureType.AUTH_FAILED) {
                nntpServers.markCredentialsInvalid(serverId);
                throw new PostingFailedException(FailureType.AUTH_REQUIRED, CANNOT_LOG_IN, messageId);
            }
            throw new PostingFailedException(FailureType.UNREACHABLE, e.getMessage(), messageId);
        }
        switch (result.outcome()) {
            case ACCEPTED -> {
            }
            case NOT_ALLOWED -> throw refused(group, FailureType.POSTING_NOT_ALLOWED, result, messageId);
            case AUTHENTICATION_REQUIRED -> throw refused(group, FailureType.AUTH_REQUIRED, result, messageId);
            case REJECTED -> {
                // The server has the article of an earlier attempt whose answer was lost (spec 009).
                if (!(retry && saysDuplicate(result.serverMessage()))) {
                    throw refused(group, FailureType.POSTING_FAILED, result, messageId);
                }
            }
        }
    }

    private static PostingFailedException refused(SubscribedGroup group, FailureType failureType,
            NntpPostResult result, String messageId) {
        // The message of the server is escaped by the NNTP adapter.
        log.warn("The server refused the post from group {}: {} ({})", group.groupId(), failureType,
                result.serverMessage());
        return new PostingFailedException(failureType, result.serverMessage(), messageId);
    }

    /** Servers word the refusal of a Message-ID they have in different ways; {@code 435} is its code elsewhere. */
    private static boolean saysDuplicate(String serverMessage) {
        String message = serverMessage.toLowerCase(Locale.ROOT);
        return message.contains("duplicate") || message.contains("435");
    }

    /** The text of the owner, followed by the signature after its separator when one is sent. */
    private static String bodyOf(Draft draft, Identity identity) {
        String text = draft.body().replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        if (!draft.includeSignature() || identity.signature() == null) {
            return text;
        }
        return text + "\n\n" + SIGNATURE_SEPARATOR + "\n" + identity.signature();
    }

    private String applicationVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "development" : build.getVersion();
    }
}
