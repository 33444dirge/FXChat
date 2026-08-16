package com.dirges.fxchat.common.protocol;

import java.util.Objects;
import java.util.UUID;

/** Cross-server mute state update. */
public record MutePacket(
        UUID updateId,
        long createdAt,
        String originServer,
        UUID playerId,
        String playerName,
        String reason,
        String mutedBy,
        long mutedAt,
        long expiresAt
) {
    public MutePacket {
        Objects.requireNonNull(updateId, "updateId");
        Objects.requireNonNull(originServer, "originServer");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(mutedBy, "mutedBy");
    }
}
