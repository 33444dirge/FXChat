package com.dirges.fxchat.common.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Small length-prefixed protocol with strict bounds at the proxy trust boundary. */
public final class PacketCodec {
    public static final int MAX_PACKET_BYTES = 64 * 1024;

    private static final int MAGIC = 0x46584348;
    private static final short VERSION = 5;
    private static final byte CHAT = 1;
    private static final byte DIRECTORY = 2;
    private static final byte PRIVATE_MESSAGE = 3;
    private static final byte MUTE = 4;
    private static final int MAX_STRING_BYTES = 16 * 1024;

    private PacketCodec() {
    }

    public static byte[] encode(ChatPacket packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeShort(VERSION);
                output.writeByte(CHAT);
                writeUuid(output, packet.messageId());
                output.writeLong(packet.createdAt());
                writeString(output, packet.originServer());
                writeString(output, packet.channel());
                writeUuid(output, packet.senderId());
                writeString(output, packet.senderName());
                writeString(output, packet.componentJson());
                writeUuids(output, packet.mentionedPlayers());
                output.writeBoolean(packet.mentionAll());
                writeShowcases(output, packet.showcases());
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("FXChat packet is too large");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode FXChat packet", exception);
        }
    }

    public static byte[] encode(DirectoryPacket packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeHeader(output, DIRECTORY);
                if (packet.players().size() > 4096) {
                    throw new IllegalArgumentException("FXChat directory is too large");
                }
                output.writeInt(packet.players().size());
                for (java.util.Map.Entry<UUID, String> entry : packet.players().entrySet()) {
                    writeUuid(output, entry.getKey());
                    writeString(output, entry.getValue());
                }
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("FXChat packet is too large");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode FXChat directory", exception);
        }
    }

    public static byte[] encode(PrivateMessagePacket packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeHeader(output, PRIVATE_MESSAGE);
                writeUuid(output, packet.messageId());
                output.writeLong(packet.createdAt());
                writeString(output, packet.originServer());
                writeUuid(output, packet.senderId());
                writeString(output, packet.senderName());
                writeUuid(output, packet.targetId());
                writeString(output, packet.targetName());
                writeString(output, packet.message());
                writeString(output, packet.componentJson());
                writeString(output, packet.receiverComponentJson());
                writeShowcases(output, packet.showcases());
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("FXChat packet is too large");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode FXChat private message", exception);
        }
    }

    public static byte[] encode(MutePacket packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeHeader(output, MUTE);
                writeUuid(output, packet.updateId());
                output.writeLong(packet.createdAt());
                writeString(output, packet.originServer());
                writeUuid(output, packet.playerId());
                writeString(output, packet.playerName());
                writeString(output, packet.reason());
                writeString(output, packet.mutedBy());
                output.writeLong(packet.mutedAt());
                output.writeLong(packet.expiresAt());
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("FXChat packet is too large");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode FXChat mute packet", exception);
        }
    }

    public static ChatPacket decode(byte[] data) throws IOException {
        Object packet = decodePacket(data);
        if (packet instanceof ChatPacket chatPacket) {
            return chatPacket;
        }
        throw new IOException("FXChat packet is not a chat packet");
    }

    public static Object decodePacket(byte[] data) throws IOException {
        if (data == null || data.length > MAX_PACKET_BYTES) {
            throw new IOException("FXChat packet is too large");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readInt() != MAGIC || input.readShort() != VERSION) {
                throw new IOException("Unknown FXChat packet header");
            }
            Object packet = switch (input.readByte()) {
                case CHAT -> readChat(input);
                case DIRECTORY -> readDirectory(input);
                case PRIVATE_MESSAGE -> readPrivateMessage(input);
                case MUTE -> readMute(input);
                default -> throw new IOException("Unknown FXChat packet type");
            };
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in FXChat packet");
            }
            return packet;
        } catch (EOFException exception) {
            throw new IOException("Truncated FXChat packet", exception);
        }
    }

    private static void writeHeader(DataOutputStream output, byte type) throws IOException {
        output.writeInt(MAGIC);
        output.writeShort(VERSION);
        output.writeByte(type);
    }

    private static ChatPacket readChat(DataInputStream input) throws IOException {
        return new ChatPacket(
                readUuid(input),
                input.readLong(),
                readString(input),
                readString(input),
                readUuid(input),
                readString(input),
                readString(input),
                readUuids(input),
                input.readBoolean(),
                readShowcases(input)
        );
    }

    private static DirectoryPacket readDirectory(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 4096 || count * 20L > input.available()) {
            throw new IOException("Invalid FXChat directory size");
        }
        java.util.LinkedHashMap<UUID, String> players = new java.util.LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            players.put(readUuid(input), readString(input));
        }
        return new DirectoryPacket(players);
    }

    private static PrivateMessagePacket readPrivateMessage(DataInputStream input) throws IOException {
        return new PrivateMessagePacket(
                readUuid(input),
                input.readLong(),
                readString(input),
                readUuid(input),
                readString(input),
                readUuid(input),
                readString(input),
                readString(input),
                readString(input),
                readString(input),
                readShowcases(input)
        );
    }

    private static MutePacket readMute(DataInputStream input) throws IOException {
        return new MutePacket(
                readUuid(input),
                input.readLong(),
                readString(input),
                readUuid(input),
                readString(input),
                readString(input),
                readString(input),
                input.readLong(),
                input.readLong()
        );
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("FXChat packet field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available()) {
            throw new IOException("Invalid FXChat packet field length");
        }
        byte[] bytes = input.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUuids(DataOutputStream output, java.util.List<UUID> values) throws IOException {
        if (values.size() > 128) {
            throw new IllegalArgumentException("Too many FXChat mention targets");
        }
        output.writeInt(values.size());
        for (UUID value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Null FXChat mention target");
            }
            writeUuid(output, value);
        }
    }

    private static java.util.List<UUID> readUuids(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 128 || count * 16L > input.available()) {
            throw new IOException("Invalid FXChat mention target count");
        }
        java.util.ArrayList<UUID> result = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(readUuid(input));
        }
        return result;
    }

    private static void writeShowcases(DataOutputStream output, Map<String, String> showcases) throws IOException {
        if (showcases.size() > 32) {
            throw new IllegalArgumentException("Too many FXChat showcases");
        }
        output.writeInt(showcases.size());
        for (Map.Entry<String, String> entry : showcases.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readShowcases(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 32) {
            throw new IOException("Invalid FXChat showcase count");
        }
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            result.put(readString(input), readString(input));
        }
        return result;
    }
}
