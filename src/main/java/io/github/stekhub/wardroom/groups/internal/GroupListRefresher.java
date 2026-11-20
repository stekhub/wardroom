package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.GroupRepository.RemovedMarking;
import io.github.stekhub.wardroom.nntp.NntpActiveGroup;
import io.github.stekhub.wardroom.nntp.NntpCapabilities;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpGroupDescription;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.platform.ProtocolAnomaly;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyKind;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * One refresh of the group list of a server (spec 003): reads {@code LIST ACTIVE} as a stream and stores it in
 * parts, marks removed and returning groups once the list is complete, then reads the descriptions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GroupListRefresher {

    /** The rest of a {@code LIST ACTIVE} line after the group name: two article numbers and a status. */
    private static final Pattern LIST_ACTIVE_DATA = Pattern.compile("\\d+\\s+\\d+\\s+\\S+");

    private final ProtocolAnomalyLog anomalyLog = new ProtocolAnomalyLog(log);

    /** Lines stored in one transaction. An interrupted refresh keeps the parts stored so far. */
    static final int BATCH_SIZE = 1_000;

    private final NntpConnections connections;
    private final GroupRepository groupRepository;
    private final TransactionOperations transactionOperations;

    /** What a refresh did, for the log. */
    record Outcome(int groupsReceived, int removed, int returned, boolean descriptionsRead) {
    }

    /**
     * Runs the refresh on one connection of the connection pool, with a single attempt.
     *
     * @param groupsReceived is told the number of groups received so far, after every group
     * @throws io.github.stekhub.wardroom.nntp.NntpException when the server could not be reached or the list did
     *                                                       not arrive completely
     */
    Outcome refresh(NntpServerSettings serverSettings, IntConsumer groupsReceived) {
        return connections.backgroundWithoutRetry(serverSettings,
                client -> refresh(serverSettings.serverId(), client, groupsReceived));
    }

    private Outcome refresh(long serverId, NntpClient client, IntConsumer groupsReceived) {
        // A list of 200,000 names needs a few megabytes (spec 003).
        Set<String> listedNames = new HashSet<>();
        InParts<NntpActiveGroup> activeGroups = new InParts<>(
                part -> transactionOperations.executeWithoutResult(status -> groupRepository.upsert(serverId, part)));
        client.listActive(activeGroup -> {
            // A server that lists a group twice has one group.
            if (listedNames.add(activeGroup.name())) {
                activeGroups.add(activeGroup);
                groupsReceived.accept(listedNames.size());
            }
        });
        activeGroups.finish();

        // Only a complete list says which groups are gone.
        RemovedMarking marking = transactionOperations.execute(status ->
                groupRepository.markRemovedAndReturned(serverId, listedNames, System.currentTimeMillis()));

        boolean descriptionsRead = offersDescriptions(client.capabilities()) && readDescriptions(serverId, client);
        return new Outcome(listedNames.size(), marking.removed(), marking.returned(), descriptionsRead);
    }

    /**
     * Groups without a line in the answer, and groups whose line has no text, keep the description they have. A
     * group whose line carries something that is not a description loses its description: the server has none.
     */
    private boolean readDescriptions(long serverId, NntpClient client) {
        InParts<NntpGroupDescription> descriptions = new InParts<>(part -> transactionOperations
                .executeWithoutResult(status -> groupRepository.updateDescriptions(serverId, part)));
        InParts<String> namesWithoutDescription = new InParts<>(part -> transactionOperations
                .executeWithoutResult(status -> groupRepository.clearDescriptions(serverId, part)));
        boolean accepted = client.listNewsgroups(description -> {
            if (description.description().isEmpty()) {
                return;
            }
            if (isListActiveData(description.description())) {
                anomalyLog.report(ProtocolAnomaly.builder()
                        .kind(ProtocolAnomalyKind.LIST_NEWSGROUPS_MALFORMED_DESCRIPTION)
                        .command("LIST NEWSGROUPS")
                        .group(description.name())
                        .action("description-ignored")
                        .raw(description.description())
                        .build());
                namesWithoutDescription.add(description.name());
            } else if (repeatsTheName(description)) {
                namesWithoutDescription.add(description.name());
            } else {
                descriptions.add(description);
            }
        });
        descriptions.finish();
        namesWithoutDescription.finish();
        return accepted;
    }

    /** Some servers fill the description of a group that has none with the name of the group. */
    static boolean repeatsTheName(NntpGroupDescription description) {
        return description.description().equalsIgnoreCase(description.name());
    }

    /** Some servers send, for a part of their groups, the numbers and the status of the group list as description. */
    static boolean isListActiveData(String description) {
        return LIST_ACTIVE_DATA.matcher(description).matches();
    }

    /**
     * Whether {@code LIST NEWSGROUPS} is worth sending. Only a capability list that names the variants of
     * {@code LIST} and leaves this one out says no. A list without the {@code LIST} line may be incomplete, and
     * older servers have no capability list at all; they are asked, and may reject the command.
     */
    static boolean offersDescriptions(NntpCapabilities capabilities) {
        List<String> listVariants = capabilities.arguments("LIST");
        return listVariants.isEmpty()
                || listVariants.stream().anyMatch(listVariant -> listVariant.equalsIgnoreCase("NEWSGROUPS"));
    }

    /** Collects items and hands them on in parts of {@link #BATCH_SIZE}. */
    private static final class InParts<T> {

        private final Consumer<List<T>> partConsumer;
        private final List<T> part = new ArrayList<>(BATCH_SIZE);

        InParts(Consumer<List<T>> partConsumer) {
            this.partConsumer = partConsumer;
        }

        void add(T item) {
            part.add(item);
            if (part.size() == BATCH_SIZE) {
                finish();
            }
        }

        void finish() {
            if (!part.isEmpty()) {
                partConsumer.accept(List.copyOf(part));
                part.clear();
            }
        }
    }
}
