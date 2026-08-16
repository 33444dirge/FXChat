package com.dirges.fxchat.common.protocol;

import java.util.Map;
import java.util.UUID;

/** Proxy-owned online player directory used by the mention function. */
public record DirectoryPacket(Map<UUID, String> players) {
    public DirectoryPacket {
        players = Map.copyOf(players == null ? Map.of() : players);
    }
}
