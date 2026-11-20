package io.github.stekhub.wardroom.nntp.internal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The overview formats of the servers, kept in memory: the order of the fields is asked once per server, not once
 * per connection (spec 005). It is asked again after a restart and after the settings of the server were saved.
 */
final class OverviewFormats {

    private final Map<Long, OverviewFormat> formatByServer = new ConcurrentHashMap<>();

    Optional<OverviewFormat> find(long serverId) {
        return Optional.ofNullable(formatByServer.get(serverId));
    }

    void remember(long serverId, OverviewFormat format) {
        formatByServer.put(serverId, format);
    }

    void forget(long serverId) {
        formatByServer.remove(serverId);
    }
}
