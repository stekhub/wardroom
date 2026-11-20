package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.SubscriptionRepository.StoredSubscription;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Response bodies of the subscription endpoints (spec 004). */
final class SubscriptionResponses {

    private SubscriptionResponses() {
    }

    @Schema(name = "Subscription")
    record SubscriptionResponse(@NotNull long groupId,
                                @NotNull long serverId,
                                @Schema(description = "Comes from the server; to be shown as text only")
                                @NotNull String name,
                                @Schema(description = "null when the server gave none; to be shown as text only")
                                String description,
                                @NotNull PostingStatus postingStatus,
                                @Schema(description = "Whether the server no longer lists the group")
                                @NotNull boolean removed,
                                @Schema(description = "Articles of the group that are not read yet")
                                @NotNull int unreadCount,
                                @NotNull SyncStatus sync) {

        /** @param synchronizing whether the group is being synchronized right now */
        static SubscriptionResponse of(StoredSubscription subscription, boolean synchronizing) {
            SyncStatus sync = new SyncStatus(synchronizing, subscription.lastSyncedAt(),
                    subscription.lastSyncError());
            return new SubscriptionResponse(subscription.groupId(), subscription.serverId(), subscription.name(),
                    subscription.description(), subscription.postingStatus(), subscription.removed(),
                    subscription.unreadCount(), sync);
        }
    }

    @Schema(name = "SubscriptionSyncStatus")
    record SyncStatus(@NotNull boolean running,
                      @Schema(description = "The time of the last successful synchronization in milliseconds; null "
                              + "when none has succeeded") Long lastSyncedAt,
                      @Schema(description = "Why the last synchronization failed; null when it succeeded")
                      String error) {
    }
}
