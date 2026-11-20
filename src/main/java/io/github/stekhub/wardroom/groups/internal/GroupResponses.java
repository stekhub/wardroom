package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.GroupRepository.StoredGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Response bodies of the group endpoints (spec 003). */
final class GroupResponses {

    private GroupResponses() {
    }

    @Schema(name = "Group")
    record GroupResponse(@NotNull long id,
                         @Schema(description = "Comes from the server; to be shown as text only") @NotNull String name,
                         @Schema(description = "null when the server gave none; to be shown as text only")
                         String description,
                         @NotNull PostingStatus postingStatus,
                         @NotNull boolean subscribed) {

        static GroupResponse of(StoredGroup group) {
            return new GroupResponse(group.id(), group.name(), group.description(), group.postingStatus(),
                    group.subscribed());
        }
    }

    @Schema(name = "GroupPage")
    record GroupPage(@NotNull List<GroupResponse> items,
                     @Schema(description = "From 0") @NotNull int page,
                     @NotNull int size,
                     @Schema(description = "The number of groups over all pages") @NotNull int total) {
    }

    @Schema(name = "GroupListRefreshStatus")
    record RefreshStatus(@NotNull boolean running,
                         @Schema(description = "The groups received by the running refresh, or by the last one")
                         @NotNull int groupsReceived,
                         @Schema(description = "The time of the last successful refresh in milliseconds; null when "
                                 + "none has succeeded") Long refreshedAt,
                         @Schema(description = "Why the last refresh failed; null when it succeeded") String error) {
    }
}
