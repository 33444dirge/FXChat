package com.dirges.fxchat.common.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable directed chat packet forwarded to the backend containing the target player. */
public record PrivateMessagePacket(
        UUID messageId,
        long createdAt,
        String originServer,
        UUID senderId,
        String senderName,
        UUID targetId,
        String targetName,
        String message,
        String componentJson,
        String receiverComponentJson,
        Map<String, String> showcases
) {
    public PrivateMessagePacket {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(originServer, "originServer");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(componentJson, "componentJson");
        Objects.requireNonNull(receiverComponentJson, "receiverComponentJson");
        showcases = Map.copyOf(showcases == null ? Map.of() : showcases);
    }
}
