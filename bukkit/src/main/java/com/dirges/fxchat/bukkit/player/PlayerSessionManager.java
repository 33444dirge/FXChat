package com.dirges.fxchat.bukkit.player;

import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.IntConsumer;

public final class PlayerSessionManager {
    private final ConcurrentHashMap<UUID, PlayerRef> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> selectedChannels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OnlinePlayer> privateTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> remoteNames = new ConcurrentHashMap<>();
    private final AtomicLong directoryVersion = new AtomicLong();
    private volatile Map<String, OnlinePlayer> nameIndex = Map.of();

    public void join(Player player) {
        UUID playerId = player.getUniqueId();
        sessions.put(playerId, new PlayerRef(playerId, new WeakReference<>(player)));
        names.put(playerId, player.getName());
        rebuildNameIndex();
    }

    public void quit(UUID playerId) {
        sessions.remove(playerId);
        removeLocalState(playerId);
    }

    private void removeLocalState(UUID playerId) {
        selectedChannels.remove(playerId);
        privateTargets.remove(playerId);
        privateTargets.entrySet().removeIf(entry -> entry.getValue().id().equals(playerId));
        names.remove(playerId);
        rebuildNameIndex();
    }

    public String selectedChannel(UUID playerId, String fallback) {
        return selectedChannels.getOrDefault(playerId, fallback);
    }

    public String activeChannel(UUID playerId, String fallback, String privateChannel) {
        return privateTargets.containsKey(playerId) ? privateChannel : selectedChannel(playerId, fallback);
    }

    public void selectChannel(UUID playerId, String channel) {
        privateTargets.remove(playerId);
        selectedChannels.put(playerId, channel);
    }

    public OnlinePlayer privateTarget(UUID playerId) {
        return privateTargets.get(playerId);
    }

    public void selectPrivateTarget(UUID playerId, OnlinePlayer target) {
        privateTargets.put(playerId, target);
    }

    public void clearPrivateTarget(UUID playerId) {
        privateTargets.remove(playerId);
    }

    public Map<String, OnlinePlayer> onlineNameIndex() {
        return nameIndex;
    }

    public long onlineDirectoryVersion() {
        return directoryVersion.get();
    }

    public String localName(UUID playerId) {
        return names.get(playerId);
    }

    public void updateRemoteNames(Map<UUID, String> directory) {
        remoteNames.clear();
        if (directory != null) {
            directory.forEach((id, name) -> {
                if (id != null && name != null && !name.isBlank() && !names.containsKey(id)) {
                    remoteNames.put(id, name);
                }
            });
        }
        rebuildNameIndex();
    }

    public List<UUID> onlineIds() {
        return List.copyOf(sessions.keySet());
    }

    public boolean isLocal(UUID playerId) {
        PlayerRef ref = sessions.get(playerId);
        return ref != null && ref.player().get() != null;
    }

    public void runAt(UUID playerId, SchedulerFacade scheduler, Consumer<Player> task) {
        PlayerRef ref = sessions.get(playerId);
        if (ref == null) {
            return;
        }
        Player player = ref.player().get();
        if (player == null) {
            removeExpiredSession(ref);
            return;
        }
        scheduler.runAtEntity(player, () -> task.accept(player));
    }

    public void broadcast(
            SchedulerFacade scheduler,
            Component component,
            Settings.ChannelSettings channel,
            PlayerSnapshot origin,
            Consumer<Player> visibleRecipient
    ) {
        broadcast(scheduler, component, channel, origin, visibleRecipient, ignored -> {
        });
    }

    public void broadcast(
            SchedulerFacade scheduler,
            Component component,
            Settings.ChannelSettings channel,
            PlayerSnapshot origin,
            Consumer<Player> visibleRecipient,
            IntConsumer deliveredCount
    ) {
        broadcast(scheduler, component, channel, origin, (player, ignored) -> true,
                visibleRecipient, deliveredCount);
    }

    public void broadcast(
            SchedulerFacade scheduler,
            Component component,
            Settings.ChannelSettings channel,
            PlayerSnapshot origin,
            BiPredicate<Player, PlayerSnapshot> recipientFilter,
            Consumer<Player> visibleRecipient,
            IntConsumer deliveredCount
    ) {
        AtomicInteger pending = new AtomicInteger(1);
        AtomicInteger delivered = new AtomicInteger();
        for (PlayerRef ref : sessions.values()) {
            Player player = ref.player().get();
            if (player == null) {
                removeExpiredSession(ref);
                continue;
            }
            pending.incrementAndGet();
            scheduler.runAtEntity(player, () -> {
                try {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (origin != null && channel.range() > 0 && !withinRange(player, origin, channel.range())) {
                        return;
                    }
                    if (!recipientFilter.test(player, origin)) {
                        return;
                    }
                    player.sendMessage(component);
                    delivered.incrementAndGet();
                    visibleRecipient.accept(player);
                } finally {
                    completeBroadcast(pending, delivered, deliveredCount);
                }
            });
        }
        completeBroadcast(pending, delivered, deliveredCount);
    }

    private static void completeBroadcast(
            AtomicInteger pending,
            AtomicInteger delivered,
            IntConsumer deliveredCount
    ) {
        if (pending.decrementAndGet() == 0) {
            deliveredCount.accept(delivered.get());
        }
    }

    public void clear() {
        sessions.clear();
        selectedChannels.clear();
        privateTargets.clear();
        names.clear();
        remoteNames.clear();
        nameIndex = Map.of();
        directoryVersion.incrementAndGet();
    }

    private void rebuildNameIndex() {
        Map<String, OnlinePlayer> result = new HashMap<>();
        remoteNames.forEach((id, name) -> result.put(
                name.toLowerCase(java.util.Locale.ROOT), new OnlinePlayer(id, name)));
        names.forEach((id, name) -> result.put(
                name.toLowerCase(java.util.Locale.ROOT), new OnlinePlayer(id, name)));
        nameIndex = Map.copyOf(result);
        directoryVersion.incrementAndGet();
    }

    private void removeExpiredSession(PlayerRef ref) {
        if (sessions.remove(ref.id(), ref)) {
            removeLocalState(ref.id());
        }
    }

    private static boolean withinRange(Player player, PlayerSnapshot origin, double range) {
        Location location = player.getLocation();
        if (!location.getWorld().getUID().equals(origin.worldId())) {
            return false;
        }
        double dx = location.getX() - origin.x();
        double dy = location.getY() - origin.y();
        double dz = location.getZ() - origin.z();
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private record PlayerRef(UUID id, WeakReference<Player> player) {
    }

    public record OnlinePlayer(UUID id, String name) {
    }
}
