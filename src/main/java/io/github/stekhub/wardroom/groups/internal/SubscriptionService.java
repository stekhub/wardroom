package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.GroupFailures.GroupNotFoundException;
import io.github.stekhub.wardroom.groups.internal.GroupFailures.GroupNotSubscribedException;
import io.github.stekhub.wardroom.groups.internal.GroupFailures.GroupRemovedException;
import io.github.stekhub.wardroom.groups.internal.SubscriptionRepository.GroupState;
import io.github.stekhub.wardroom.groups.internal.SubscriptionResponses.SubscriptionResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Subscribing, unsubscribing, the subscription list and marking a group as read (spec 004). */
@Slf4j
@Service
@RequiredArgsConstructor
class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final TransactionOperations transactionOperations;

    List<SubscriptionResponse> subscriptions() {
        return subscriptionRepository.findAll().stream().map(SubscriptionResponse::of).toList();
    }

    /**
     * Subscribing to a subscribed group has no effect, also when the server has removed the group since.
     *
     * @throws GroupNotFoundException when no group has this ID
     * @throws GroupRemovedException  when the server no longer lists the group
     */
    void subscribe(long groupId) {
        boolean added = Boolean.TRUE.equals(transactionOperations.execute(status -> {
            GroupState groupState = subscriptionRepository.findGroupState(groupId)
                    .orElseThrow(GroupNotFoundException::new);
            if (groupState.subscribed()) {
                return false;
            }
            if (groupState.removed()) {
                throw new GroupRemovedException();
            }
            subscriptionRepository.subscribe(groupId);
            return true;
        }));
        if (added) {
            log.info("The group {} was subscribed", groupId);
        }
    }

    /**
     * Unsubscribing from a group that is not subscribed has no effect.
     *
     * @throws GroupNotFoundException when no group has this ID
     */
    void unsubscribe(long groupId) {
        boolean removed = Boolean.TRUE.equals(transactionOperations.execute(status -> {
            GroupState groupState = subscriptionRepository.findGroupState(groupId)
                    .orElseThrow(GroupNotFoundException::new);
            if (!groupState.subscribed()) {
                return false;
            }
            subscriptionRepository.unsubscribe(groupId);
            return true;
        }));
        if (removed) {
            log.info("The group {} was unsubscribed and its cached articles were removed", groupId);
        }
    }

    /**
     * @throws GroupNotFoundException      when no group has this ID
     * @throws GroupNotSubscribedException when the group is not subscribed
     */
    void markAllRead(long groupId) {
        long now = System.currentTimeMillis();
        transactionOperations.executeWithoutResult(status -> {
            GroupState groupState = subscriptionRepository.findGroupState(groupId)
                    .orElseThrow(GroupNotFoundException::new);
            if (!groupState.subscribed()) {
                throw new GroupNotSubscribedException();
            }
            int marked = subscriptionRepository.markAllRead(groupId, now);
            log.debug("{} articles of the group {} were marked as read", marked, groupId);
        });
    }
}
