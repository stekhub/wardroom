package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.GroupFailures.ServerNotFoundException;
import io.github.stekhub.wardroom.groups.internal.GroupResponses.GroupPage;
import io.github.stekhub.wardroom.groups.internal.GroupResponses.GroupResponse;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.server.GroupListStates;
import io.github.stekhub.wardroom.settings.Settings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Browsing and searching the group list of a server (spec 003), without binary and ignored groups (spec 022). */
@Service
@RequiredArgsConstructor
class GroupListService {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_QUERY_LENGTH = 200;

    private final GroupListStates groupListStates;
    private final GroupRepository groupRepository;
    private final Settings settings;

    /**
     * @param query the search text, or {@code null} for all groups
     * @param sort  the order as the request names it, or {@code null} for the order by name
     * @throws ServerNotFoundException  when no server has this ID
     * @throws FieldValidationException when a parameter breaks a rule
     */
    GroupPage page(long serverId, String query, String sort, int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "The page must be 0 or higher.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "The page size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new FieldValidationException("q",
                    "The search text must not be longer than " + MAX_QUERY_LENGTH + " characters.");
        }
        GroupSort groupSort = GroupSort.ofParameter(sort);
        if (groupListStates.find(serverId).isEmpty()) {
            throw new ServerNotFoundException();
        }
        List<String> searchWords = searchWords(query);
        boolean hideBinaryGroups = settings.hideBinaryGroups();
        List<GroupResponse> items = groupRepository
                .findPage(serverId, searchWords, hideBinaryGroups, groupSort, (long) page * size, size)
                .stream()
                .map(GroupResponse::of)
                .toList();
        return new GroupPage(items, page, size, groupRepository.count(serverId, searchWords, hideBinaryGroups),
                groupRepository.hasActivity(serverId));
    }

    /** The query is split into words at whitespace; a group matches when it has every word (spec 003). */
    static List<String> searchWords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return List.of(query.strip().split("\\s+"));
    }
}
