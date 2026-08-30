package com.dirges.fxchat.bukkit.moderation;

import java.util.Objects;

public record GlobalMuteRecord(
        String reason,
        String mutedBy,
        long mutedAt,
        long expiresAt
) {
    public GlobalMuteRecord {
        reason = Objects.requireNonNull(reason, "reason").trim();
        mutedBy = Objects.requireNonNull(mutedBy, "mutedBy").trim();
        if (reason.isBlank() || mutedBy.isBlank() || mutedAt < 0
                || expiresAt < 0 || (expiresAt != 0 && expiresAt <= mutedAt)) {
            throw new IllegalArgumentException("Invalid global mute record");
        }
    }

    public boolean activeAt(long now) {
        return expiresAt != 0 && expiresAt <= now;
    }
}
