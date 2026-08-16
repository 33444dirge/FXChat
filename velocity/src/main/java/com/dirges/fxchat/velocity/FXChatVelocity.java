package com.dirges.fxchat.velocity;

import com.dirges.fxchat.common.protocol.ChatPacket;
import com.dirges.fxchat.common.protocol.DirectoryPacket;
import com.dirges.fxchat.common.protocol.PacketCodec;
import com.dirges.fxchat.common.protocol.PrivateMessagePacket;
import com.dirges.fxchat.common.protocol.MutePacket;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.plugin.Plugin;
import com.google.inject.Inject;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "fxchat",
        name = "FXChat",
        version = "1.0.0-SNAPSHOT",
        authors = {"33444_dirge"}
)
public final class FXChatVelocity {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("fxchat", "main");
    private static final long MIN_FORWARD_INTERVAL_MILLIS = 100L;

    private final ProxyServer proxy;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Long> lastForward = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final Object directoryLock = new Object();
    private volatile ScheduledTask directoryTask;
    private volatile boolean enabled;

    @Inject
    public FXChatVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getAllPlayers().forEach(player -> playerNames.put(player.getUniqueId(), player.getUsername()));
        enabled = true;
        requestDirectoryBroadcast();
        logger.info("FXChat Velocity bridge enabled.");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        playerNames.put(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
        requestDirectoryBroadcast();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerNames.remove(playerId);
        lastForward.remove(playerId);
        requestDirectoryBroadcast();
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        requestDirectoryBroadcast();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!enabled || !CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        Player player;
        String origin;
        if (event.getSource() instanceof ServerConnection sourceServer) {
            player = sourceServer.getPlayer();
            origin = sourceServer.getServerInfo().getName();
        } else if (event.getSource() instanceof Player sourcePlayer) {
            Optional<ServerConnection> sourceServer = sourcePlayer.getCurrentServer();
            if (sourceServer.isEmpty()) {
                return;
            }
            player = sourcePlayer;
            origin = sourceServer.get().getServerInfo().getName();
        } else {
            return;
        }
        if (!tryAcquire(player.getUniqueId())) {
            return;
        }

        try {
            Object decoded = PacketCodec.decodePacket(event.getData());
            if (decoded instanceof ChatPacket received) {
                ChatPacket packet = new ChatPacket(
                        received.messageId(),
                        received.createdAt(),
                        origin,
                        received.channel(),
                        received.senderId(),
                        received.senderName(),
                        received.componentJson(),
                        received.mentionedPlayers(),
                        received.mentionAll(),
                        received.showcases()
                );
                forward(packet, origin);
            } else if (decoded instanceof PrivateMessagePacket received) {
                PrivateMessagePacket packet = new PrivateMessagePacket(
                        received.messageId(),
                        received.createdAt(),
                        origin,
                        received.senderId(),
                        received.senderName(),
                        received.targetId(),
                        received.targetName(),
                        received.message(),
                        received.componentJson(),
                        received.receiverComponentJson(),
                        received.showcases()
                );
                forward(packet, origin);
            } else if (decoded instanceof MutePacket received) {
                MutePacket packet = new MutePacket(
                        received.updateId(),
                        received.createdAt(),
                        origin,
                        received.playerId(),
                        received.playerName(),
                        received.reason(),
                        received.mutedBy(),
                        received.mutedAt(),
                        received.expiresAt()
                );
                forward(packet, origin);
            }
        } catch (IOException | RuntimeException exception) {
            logger.warn("Dropped malformed FXChat backend packet", exception);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        enabled = false;
        synchronized (directoryLock) {
            if (directoryTask != null) {
                directoryTask.cancel();
                directoryTask = null;
            }
        }
        proxy.getChannelRegistrar().unregister(CHANNEL);
        lastForward.clear();
        playerNames.clear();
    }

    private void forward(ChatPacket packet, String originServer) {
        byte[] data;
        try {
            data = PacketCodec.encode(packet);
        } catch (RuntimeException exception) {
            logger.warn("Could not encode FXChat packet for forwarding", exception);
            return;
        }

        Collection<RegisteredServer> servers = proxy.getAllServers();
        for (RegisteredServer server : servers) {
            if (server.getServerInfo().getName().equalsIgnoreCase(originServer)) {
                continue;
            }
            server.sendPluginMessage(CHANNEL, data);
        }
    }

    private void forward(PrivateMessagePacket packet, String originServer) {
        byte[] data;
        try {
            data = PacketCodec.encode(packet);
        } catch (RuntimeException exception) {
            logger.warn("Could not encode FXChat private packet for forwarding", exception);
            return;
        }

        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equalsIgnoreCase(originServer)) {
                continue;
            }
            server.sendPluginMessage(CHANNEL, data);
        }
    }

    private void forward(MutePacket packet, String originServer) {
        byte[] data;
        try {
            data = PacketCodec.encode(packet);
        } catch (RuntimeException exception) {
            logger.warn("Could not encode FXChat mute packet for forwarding", exception);
            return;
        }

        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equalsIgnoreCase(originServer)) {
                continue;
            }
            server.sendPluginMessage(CHANNEL, data);
        }
    }

    private void broadcastDirectory() {
        if (!enabled) {
            return;
        }
        byte[] data;
        try {
            data = PacketCodec.encode(new DirectoryPacket(playerNames));
        } catch (RuntimeException exception) {
            logger.warn("Could not encode FXChat player directory", exception);
            return;
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            server.sendPluginMessage(CHANNEL, data);
        }
    }

    private void requestDirectoryBroadcast() {
        if (!enabled) {
            return;
        }
        synchronized (directoryLock) {
            if (directoryTask != null) {
                return;
            }
            directoryTask = proxy.getScheduler().buildTask(this, () -> {
                synchronized (directoryLock) {
                    directoryTask = null;
                }
                broadcastDirectory();
            }).delay(100L, TimeUnit.MILLISECONDS).schedule();
        }
    }

    private boolean tryAcquire(UUID playerId) {
        long now = System.currentTimeMillis();
        boolean[] accepted = {false};
        lastForward.compute(playerId, (ignored, previous) -> {
            if (previous == null || now - previous >= MIN_FORWARD_INTERVAL_MILLIS) {
                accepted[0] = true;
                return now;
            }
            return previous;
        });
        if (lastForward.size() > 4096) {
            lastForward.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
        }
        return accepted[0];
    }
}
