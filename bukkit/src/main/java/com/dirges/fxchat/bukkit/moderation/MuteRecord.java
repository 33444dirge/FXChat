package com.dirges.fxchat.bukkit.moderation;

import java.util.Objects;
import java.util.UUID;

public record MuteRecord(
        UUID playerId,
        String playerName,
        String reason,
        String mutedBy,
        long mutedAt,
        long expiresAt
) {
    public MuteRecord {
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName").trim();
        reason = Objects.requireNonNull(reason, "reason").trim();
        mutedBy = Objects.requireNonNull(mutedBy, "mutedBy").trim();
        if (playerName.isBlank() || reason.isBlank() || mutedBy.isBlank()) {
            throw new IllegalArgumentException("Mute record contains a blank value");
        }
        if (mutedAt < 0 || expiresAt < 0 || (expiresAt != 0 && expiresAt <= mutedAt)) {
            throw new IllegalArgumentException("Invalid mute timestamps");
        }
    }

    public boolean activeAt(long now) {
        return expiresAt != 0 && expiresAt <= now;
    }
}
