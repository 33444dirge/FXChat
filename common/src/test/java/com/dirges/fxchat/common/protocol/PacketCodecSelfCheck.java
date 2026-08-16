package com.dirges.fxchat.common.protocol;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PacketCodecSelfCheck {
    private PacketCodecSelfCheck() {
    }

    public static void main(String[] args) throws IOException {
        ChatPacket packet = new ChatPacket(
                UUID.randomUUID(),
                1234L,
                "survival-1",
                "global",
                UUID.randomUUID(),
                "Dirge",
                "{\"text\":\"hello\"}",
                List.of(UUID.randomUUID()),
                true,
                Map.of("token", "ITEM|dGVzdA|0:STONE:1:")
        );
        if (!packet.equals(PacketCodec.decode(PacketCodec.encode(packet)))) {
            throw new AssertionError("Packet round-trip failed");
        }
        DirectoryPacket directory = new DirectoryPacket(Map.of(UUID.randomUUID(), "Dirge"));
        if (!directory.equals(PacketCodec.decodePacket(PacketCodec.encode(directory)))) {
            throw new AssertionError("Directory round-trip failed");
        }
        PrivateMessagePacket privateMessage = new PrivateMessagePacket(
                UUID.randomUUID(),
                1235L,
                "survival-1",
                UUID.randomUUID(),
                "Dirge",
                UUID.randomUUID(),
                "Alex",
                "private text",
                "{\"text\":\"private\"}",
                "{\"text\":\"private receiver\"}",
                Map.of("token", "ITEM|dGVzdA|0:STONE:1:")
        );
        if (!privateMessage.equals(PacketCodec.decodePacket(PacketCodec.encode(privateMessage)))) {
            throw new AssertionError("Private message round-trip failed");
        }
        MutePacket mute = new MutePacket(
                UUID.randomUUID(),
                1236L,
                "survival-1",
                UUID.randomUUID(),
                "Alex",
                "spam",
                "Dirge",
                1236L,
                4567L
        );
        if (!mute.equals(PacketCodec.decodePacket(PacketCodec.encode(mute)))) {
            throw new AssertionError("Mute round-trip failed");
        }
        try {
            PacketCodec.decode(new byte[]{0, 1, 2});
            throw new AssertionError("Malformed packet was accepted");
        } catch (IOException expected) {
            // Expected validation failure.
        }
    }
}
