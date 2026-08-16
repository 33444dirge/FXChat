package com.dirges.fxchat.bukkit.proxy;

import com.dirges.fxchat.bukkit.FXChatBukkit;
import com.dirges.fxchat.common.protocol.ChatPacket;
import com.dirges.fxchat.common.protocol.DirectoryPacket;
import com.dirges.fxchat.common.protocol.PacketCodec;
import com.dirges.fxchat.common.protocol.PrivateMessagePacket;
import com.dirges.fxchat.common.protocol.MutePacket;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BukkitProxyTransport implements PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "fxchat:main";

    private final FXChatBukkit plugin;
    private final Consumer<Object> receiver;
    private final Consumer<DirectoryPacket> directoryReceiver;
    private volatile boolean enabled;

    public BukkitProxyTransport(
            FXChatBukkit plugin,
            Consumer<Object> receiver,
            Consumer<DirectoryPacket> directoryReceiver
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.directoryReceiver = Objects.requireNonNull(directoryReceiver, "directoryReceiver");
    }

    public void enable() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        enabled = true;
    }

    public void send(Player carrier, ChatPacket packet) {
        if (enabled) {
            carrier.sendPluginMessage(plugin, CHANNEL, PacketCodec.encode(packet));
        }
    }

    public void send(Player carrier, PrivateMessagePacket packet) {
        if (enabled) {
            carrier.sendPluginMessage(plugin, CHANNEL, PacketCodec.encode(packet));
        }
    }

    public void send(Player carrier, MutePacket packet) {
        if (enabled) {
            carrier.sendPluginMessage(plugin, CHANNEL, PacketCodec.encode(packet));
        }
    }

    @Override
    public void onPluginMessageReceived(@NonNull String channel, @NonNull Player carrier, byte @NonNull [] data) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try {
            Object packet = PacketCodec.decodePacket(data);
            if (packet instanceof ChatPacket || packet instanceof PrivateMessagePacket
                    || packet instanceof MutePacket) {
                receiver.accept(packet);
            } else if (packet instanceof DirectoryPacket directoryPacket) {
                directoryReceiver.accept(directoryPacket);
            }
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Dropped malformed FXChat proxy packet", exception);
        }
    }

    @Override
    public void close() {
        enabled = false;
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
    }
}
