package com.dirges.fxchat.common.protocol;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable wire-level chat message shared by the Bukkit and Velocity jars. */
public record ChatPacket(
        UUID messageId,
        long createdAt,
        String originServer,
        String channel,
        UUID senderId,
        String senderName,
        String componentJson,
        List<UUID> mentionedPlayers,
        boolean mentionAll,
        Map<String, String> showcases
) {
    public ChatPacket(
            UUID messageId,
            long createdAt,
            String originServer,
            String channel,
            UUID senderId,
            String senderName,
            String componentJson
    ) {
        this(messageId, createdAt, originServer, channel, senderId, senderName, componentJson,
                List.of(), false, Map.of());
    }

    public ChatPacket {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(originServer, "originServer");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(componentJson, "componentJson");
        mentionedPlayers = List.copyOf(mentionedPlayers == null ? List.of() : mentionedPlayers);
        showcases = Map.copyOf(showcases == null ? Map.of() : showcases);
    }
}
