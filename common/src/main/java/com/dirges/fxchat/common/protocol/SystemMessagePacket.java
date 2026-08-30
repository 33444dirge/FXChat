package com.dirges.fxchat.common.protocol;

import java.util.Objects;
import java.util.UUID;

/** A MiniMessage system message for one backend or every backend. */
public record SystemMessagePacket(
        UUID messageId,
        long createdAt,
        String originServer,
        UUID targetId,
        String message
) {
    public SystemMessagePacket {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(originServer, "originServer");
        Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 16_384) {
            throw new IllegalArgumentException("System message is blank or too long");
        }
    }
}
