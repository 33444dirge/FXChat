package com.dirges.fxchat.bukkit.chat;

import com.dirges.fxchat.bukkit.FXChatBukkit;
import com.dirges.fxchat.bukkit.api.event.FXChatSendEvent;
import com.dirges.fxchat.bukkit.config.MessageService;
import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.function.ChatFunctionService;
import com.dirges.fxchat.bukkit.hook.CustomNameplatesHook;
import com.dirges.fxchat.bukkit.moderation.MuteRecord;
import com.dirges.fxchat.bukkit.moderation.MuteService;
import com.dirges.fxchat.bukkit.moderation.IgnoreService;
import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.player.PlayerSnapshot;
import com.dirges.fxchat.bukkit.protocol.SeenMessages;
import com.dirges.fxchat.bukkit.proxy.BukkitProxyTransport;
import com.dirges.fxchat.bukkit.render.MessageRenderer;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.dirges.fxchat.bukkit.script.ChatScriptService;
import com.dirges.fxchat.common.protocol.ChatPacket;
import com.dirges.fxchat.common.protocol.PrivateMessagePacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ChatService implements AutoCloseable {
    private final FXChatBukkit plugin;
    private final SchedulerFacade scheduler;
    private final MessageService messages;
    private final PlayerSessionManager sessions;
    private final MessageRenderer renderer;
    private final ChatFunctionService functions;
    private final ChatScriptService scripts;
    private final BukkitProxyTransport transport;
    private final CustomNameplatesHook customNameplates;
    private final MuteService muteService;
    private final IgnoreService ignoreService;
    private final ChatFilterService filters;
    private Consumer<Player> leaveExternalChat;
    private final SeenMessages seenMessages = new SeenMessages();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LastMessage> lastMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReplyTarget> replyTargets = new ConcurrentHashMap<>();
    private final Set<UUID> privateSpies = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Settings settings;

    public ChatService(
            FXChatBukkit plugin,
            SchedulerFacade scheduler,
            MessageService messages,
            Settings settings,
            PlayerSessionManager sessions,
            MessageRenderer renderer,
            ChatFunctionService functions,
            ChatScriptService scripts,
            BukkitProxyTransport transport,
            Consumer<Player> leaveExternalChat,
            CustomNameplatesHook customNameplates,
            MuteService muteService,
            IgnoreService ignoreService,
            ChatFilterService filters
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.messages = messages;
        this.settings = settings;
        this.sessions = sessions;
        this.renderer = renderer;
        this.functions = functions;
        this.scripts = scripts;
        this.transport = transport;
        this.customNameplates = customNameplates;
        this.muteService = muteService;
        this.ignoreService = ignoreService;
        this.filters = filters;
        this.leaveExternalChat = leaveExternalChat;
    }

    public void updateSettings(Settings settings) {
        this.settings = settings;
    }

    public boolean togglePrivateSpy(UUID playerId) {
        if (privateSpies.remove(playerId)) {
            return false;
        }
        privateSpies.add(playerId);
        return true;
    }

    public boolean setPrivateSpy(UUID playerId, boolean enabled) {
        if (enabled) {
            privateSpies.add(playerId);
        } else {
            privateSpies.remove(playerId);
        }
        return enabled;
    }

    public void sendPrivateMessage(Player sender, String targetName, String rawMessage) {
        if (targetName == null || targetName.isBlank()) {
            messages.send(sender, "private.player-not-found");
            return;
        }
        PlayerSessionManager.OnlinePlayer target = sessions.onlineNameIndex()
                .get(targetName.toLowerCase(Locale.ROOT));
        if (target == null) {
            messages.send(sender, "private.player-not-found");
            return;
        }
        leaveExternalChat.accept(sender);
        sendPrivateMessage(sender, target, rawMessage);
    }

    public void enterPrivateChannel(Player sender, String targetName) {
        if (closed.get() || !sender.isOnline()) {
            return;
        }
        Settings current = settings;
        if (!sender.hasPermission("fxchat.chat")) {
            messages.send(sender, "chat.no-permission");
            return;
        }
        if (targetName == null || targetName.isBlank()) {
            messages.send(sender, "private.player-required");
            return;
        }
        PlayerSessionManager.OnlinePlayer target = sessions.onlineNameIndex()
                .get(targetName.toLowerCase(Locale.ROOT));
        if (target == null || (!sessions.isLocal(target.id()) && !current.proxyEnabled())) {
            messages.send(sender, "private.player-not-found");
            return;
        }
        if (target.id().equals(sender.getUniqueId())) {
            messages.send(sender, "private.self");
            return;
        }
        String previousChannel = sessions.activeChannel(
                sender.getUniqueId(), current.defaultChannel(), current.privateChannel());
        if (previousChannel.equalsIgnoreCase(current.privateChannel())) {
            if (selectChannel(sender, current.defaultChannel())) {
                messages.send(sender, "command.channel-selected", Map.of(
                        "channel", current.defaultChannel()));
            }
            return;
        }
        leaveExternalChat.accept(sender);
        sessions.selectPrivateTarget(sender.getUniqueId(), target);
        messages.send(sender, "private.channel-entered", Map.of("player", target.name()));
        if (!previousChannel.equals(current.privateChannel())) {
            scripts.triggerChannelSwitch(
                    sender, previousChannel, current.privateChannel(), current.serverName(), current.privateChannel());
        }
    }

    public void replyPrivateMessage(Player sender, String rawMessage) {
        ReplyTarget target = replyTargets.get(sender.getUniqueId());
        if (target == null) {
            messages.send(sender, "private.no-reply");
            return;
        }
        leaveExternalChat.accept(sender);
        sendPrivateMessage(sender, new PlayerSessionManager.OnlinePlayer(target.id(), target.name()), rawMessage);
    }

    public void handleChat(Player player, String rawMessage) {
        handleChat(player, rawMessage, null);
    }

    public void handleSudo(
            Player executor,
            String targetName,
            String rawMessage,
            String requestedChannel,
            String privateTargetName
    ) {
        if (closed.get() || !executor.isOnline()) {
            return;
        }
        if (!executor.hasPermission("fxchat.sudo")) {
            messages.send(executor, "command.no-permission");
            return;
        }
        PlayerSessionManager.OnlinePlayer target = targetName == null ? null
                : sessions.onlineNameIndex().get(targetName.toLowerCase(Locale.ROOT));
        if (target == null || !sessions.isLocal(target.id())) {
            messages.send(executor, "command.sudo-target-not-found");
            return;
        }
        Settings current = settings;
        String channelId = requestedChannel == null
                ? sessions.activeChannel(
                executor.getUniqueId(), current.defaultChannel(), current.privateChannel())
                : current.resolveChannel(requestedChannel);
        if (channelId == null) {
            messages.send(executor, "chat.channel-not-found");
            return;
        }
        if (channelId.equals(current.privateChannel())) {
            if (privateTargetName == null || privateTargetName.isBlank()) {
                messages.send(executor, "command.sudo-private-target-required");
                return;
            }
            PlayerSessionManager.OnlinePlayer privateTarget = sessions.onlineNameIndex()
                    .get(privateTargetName.toLowerCase(Locale.ROOT));
            if (privateTarget == null || (!sessions.isLocal(privateTarget.id()) && !current.proxyEnabled())) {
                messages.send(executor, "private.player-not-found");
                return;
            }
            sessions.runAt(target.id(), scheduler, targetPlayer ->
                    sendPrivateMessage(targetPlayer, privateTarget.name(), rawMessage));
            return;
        }
        sessions.runAt(target.id(), scheduler, targetPlayer ->
                sendPublicMessage(targetPlayer, channelId, rawMessage));
    }

    public String channelAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return settings.resolveChannel(alias.toLowerCase(Locale.ROOT));
    }

    public boolean selectChannel(Player player, String channel) {
        Settings current = settings;
        String previousChannel = current.resolveChannel(sessions.activeChannel(
                player.getUniqueId(), current.defaultChannel(), current.privateChannel()));
        if (previousChannel == null) {
            previousChannel = current.defaultChannel();
        }
        String selectedChannel = current.resolveChannel(channel);
        if (selectedChannel == null) {
            return false;
        }
        if (previousChannel.equalsIgnoreCase(selectedChannel)) {
            if (previousChannel.equalsIgnoreCase(current.defaultChannel())) {
                return false;
            }
            selectedChannel = current.defaultChannel();
        }
        leaveExternalChat.accept(player);
        sessions.selectChannel(player.getUniqueId(), selectedChannel);
        scripts.triggerChannelSwitch(
                player, previousChannel, selectedChannel, current.serverName(), current.privateChannel());
        return true;
    }

    public String activeChannel(Player player) {
        Settings current = settings;
        return sessions.activeChannel(player.getUniqueId(), current.defaultChannel(), current.privateChannel());
    }

    public void handleChannelAlias(Player player, String channelId, String rawMessage) {
        if (closed.get() || !player.isOnline()) {
            return;
        }
        Settings current = settings;
        Settings.ChannelSettings channel = current.channel(channelId);
        if (channel == null) {
            return;
        }
        if (channel.id().equals(current.privateChannel())) {
            messages.send(player, "private.player-required");
            return;
        }
        if (!channel.permission().isBlank() && !player.hasPermission(channel.permission())) {
            messages.send(player, "chat.channel-no-permission");
            return;
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            boolean selected = selectChannel(player, channel.id());
            messages.send(player, selected
                    ? "command.channel-selected" : "command.channel-already-selected",
                    Map.of("channel", activeChannel(player)));
            return;
        }
        handleChat(player, rawMessage, channel.id());
    }

    private void handleChat(Player player, String rawMessage, String forcedChannel) {
        if (closed.get() || !player.isOnline() || rawMessage == null) {
            return;
        }
        Settings current = settings;
        if (!player.hasPermission("fxchat.chat")) {
            messages.send(player, "chat.no-permission");
            return;
        }
        if (isMuted(player)) {
            return;
        }
        leaveExternalChat.accept(player);

        String channelId = forcedChannel == null
                ? sessions.selectedChannel(player.getUniqueId(), current.defaultChannel())
                : forcedChannel;
        String message = rawMessage;
        boolean prefixChannelSelected = false;
        if (forcedChannel == null && !current.globalPrefix().isEmpty() && message.startsWith(current.globalPrefix())) {
            String prefixChannel = current.resolveChannel(current.prefixChannel());
            if (prefixChannel != null) {
                channelId = prefixChannel;
                prefixChannelSelected = true;
                message = message.length() > current.globalPrefix().length()
                        ? message.substring(current.globalPrefix().length()).stripLeading()
                        : "";
            }
        }

        if (forcedChannel == null && !prefixChannelSelected) {
            PlayerSessionManager.OnlinePlayer privateTarget = sessions.privateTarget(player.getUniqueId());
            if (privateTarget != null) {
                PlayerSessionManager.OnlinePlayer currentTarget = sessions.onlineNameIndex()
                        .get(privateTarget.name().toLowerCase(Locale.ROOT));
                if (currentTarget == null || !currentTarget.id().equals(privateTarget.id())) {
                    sessions.clearPrivateTarget(player.getUniqueId());
                    messages.send(player, "private.player-not-found");
                    return;
                }
                sendPrivateMessage(player, currentTarget, message);
                return;
            }
        }

        sendPublicMessageChecked(player, channelId, message);
    }

    private void sendPublicMessage(Player player, String channelId, String rawMessage) {
        if (closed.get() || !player.isOnline() || rawMessage == null) {
            return;
        }
        if (!player.hasPermission("fxchat.chat")) {
            messages.send(player, "chat.no-permission");
            return;
        }
        if (isMuted(player)) {
            return;
        }
        leaveExternalChat.accept(player);
        sendPublicMessageChecked(player, channelId, rawMessage);
    }

    private void sendPublicMessageChecked(Player player, String channelId, String rawMessage) {
        Settings current = settings;
        Settings.ChannelSettings channel = current.channel(channelId);
        if (channel == null) {
            channel = current.channels().values().iterator().next();
        }
        if (!channel.permission().isBlank() && !player.hasPermission(channel.permission())) {
            messages.send(player, "chat.channel-no-permission");
            return;
        }
        if (rawMessage.isBlank()) {
            return;
        }
        if (rawMessage.length() > current.maxMessageLength()) {
            messages.send(player, "chat.message-too-long");
            return;
        }

        FXChatSendEvent event = new FXChatSendEvent(player, channel.id(), rawMessage);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        String eventChannel = current.resolveChannel(event.getChannel());
        channel = current.channel(eventChannel);
        if (channel == null) {
            messages.send(player, "chat.channel-not-found");
            return;
        }
        if (!channel.permission().isBlank() && !player.hasPermission(channel.permission())) {
            messages.send(player, "chat.channel-no-permission");
            return;
        }
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        if (message.length() > current.maxMessageLength()) {
            messages.send(player, "chat.message-too-long");
            return;
        }
        if (!tryAcquireCooldown(player.getUniqueId(), current.cooldownMillis())) {
            messages.send(player, "chat.slow-down");
            return;
        }

        message = filters.filterChat(message);
        if (!tryRecordMessage(player.getUniqueId(), message, current.antiRepeatWindowMillis(),
                current.antiRepeatSimilarity())) {
            messages.send(player, "chat.repeated-message");
            return;
        }
        PlayerSnapshot snapshot = snapshot(player);
        MessageRenderer.RenderedMessage rendered = renderer.render(player, current, channel, message, false);
        Component component = rendered.component();
        UUID messageId = UUID.randomUUID();
        String deliveredChannelId = channel.id();
        double deliveredChannelRange = channel.range();
        seenMessages.markIfNew(messageId);
        sessions.broadcast(scheduler, component, channel, snapshot,
                (recipient, ignoredOrigin) -> !ignoreService.ignores(recipient.getUniqueId(), snapshot.name()),
                recipient -> functions.notifyMention(
                recipient,
                player.getUniqueId(),
                player.getName(),
                rendered.mentionedPlayers(),
                rendered.mentionAll()),
                delivered -> {
                    if (deliveredChannelRange > 0 && delivered <= 1) {
                        scheduler.runAtEntity(player, () -> {
                            if (player.isOnline()) {
                                messages.sendActionBar(player, "chat.no-one-heard",
                                        Map.of("channel", deliveredChannelId));
                            }
                        });
                    }
                });
        if (customNameplates != null) {
            customNameplates.onPublicChat(
                    player,
                    renderer.prepareInput(player, message, MessageRenderer.ColorTarget.CHAT),
                    channel.id(),
                    renderer.hasColorPermission(player, MessageRenderer.ColorTarget.CHAT, "minimessages"),
                    renderer.hasColorPermission(player, MessageRenderer.ColorTarget.CHAT, "legacy"));
        }
        if (current.proxyEnabled() && channel.crossServer()) {
            ChatPacket packet = new ChatPacket(
                    messageId,
                    System.currentTimeMillis(),
                    current.serverName(),
                    channel.id(),
                    snapshot.id(),
                    snapshot.name(),
                    GsonComponentSerializer.gson().serialize(component),
                    java.util.List.copyOf(rendered.mentionedPlayers()),
                    rendered.mentionAll(),
                    rendered.showcases()
            );
            transport.send(player, packet);
        }
        scripts.trigger(player, message, channel.id(), current.serverName());
    }

    public void receiveRemote(ChatPacket packet) {
        if (closed.get() || !seenMessages.markIfNew(packet.messageId())) {
            return;
        }
        Settings current = settings;
        if (current.serverName().equalsIgnoreCase(packet.originServer())) {
            return;
        }
        Settings.ChannelSettings channel = current.channel(packet.channel());
        if (channel == null || !channel.crossServer()) {
            return;
        }
        functions.importShowcases(packet.showcases());
        Set<UUID> mentionedPlayers = Set.copyOf(packet.mentionedPlayers());
        try {
            Component component = GsonComponentSerializer.gson().deserialize(packet.componentJson());
            sessions.broadcast(scheduler, component, channel, null,
                    (recipient, ignoredOrigin) -> !ignoreService.ignores(recipient.getUniqueId(), packet.senderName()),
                    recipient -> functions.notifyMention(
                    recipient,
                    packet.senderId(),
                    packet.senderName(),
                    mentionedPlayers,
                    packet.mentionAll()),
                    ignored -> { });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Dropped FXChat message with invalid component data");
        }
    }

    public void receivePrivateRemote(PrivateMessagePacket packet) {
        Settings current = settings;
        boolean targetLocal = sessions.isLocal(packet.targetId());
        if (closed.get() || !seenMessages.markIfNew(packet.messageId())
                || (!targetLocal && privateSpies.isEmpty())) {
            return;
        }
        functions.importShowcases(packet.showcases());
        try {
            Component receiverComponent = GsonComponentSerializer.gson().deserialize(packet.receiverComponentJson());
            Component spyComponent = GsonComponentSerializer.gson().deserialize(packet.componentJson());
            String senderName = packet.senderName();
            if (targetLocal) {
                sessions.runAt(packet.targetId(), scheduler, target -> {
                    if (ignoreService.ignores(target.getUniqueId(), senderName)) {
                        return;
                    }
                    target.sendMessage(receiverComponent);
                    replyTargets.put(packet.targetId(), new ReplyTarget(packet.senderId(), senderName));
                    scripts.triggerPrivateReceived(
                            target, packet.senderName(), packet.message(), packet.originServer(),
                            current.privateChannel());
                });
            }
            sendPrivateSpyMessage(
                    packet.senderId(),
                    packet.targetId(),
                    spyComponent
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Dropped FXChat private message with invalid component data");
        }
    }

    public void removePlayer(UUID playerId) {
        cooldowns.remove(playerId);
        lastMessages.remove(playerId);
        functions.removePlayer(playerId);
        replyTargets.remove(playerId);
        privateSpies.remove(playerId);
        replyTargets.entrySet().removeIf(entry -> entry.getValue().id().equals(playerId));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cooldowns.clear();
            lastMessages.clear();
            replyTargets.clear();
            privateSpies.clear();
            seenMessages.clear();
            leaveExternalChat = player -> { };
        }
    }

    private void sendPrivateMessage(
            Player sender,
            PlayerSessionManager.OnlinePlayer target,
            String rawMessage
    ) {
        if (closed.get() || !sender.isOnline()) {
            return;
        }
        if (!sender.hasPermission("fxchat.chat")) {
            messages.send(sender, "chat.no-permission");
            return;
        }
        if (isMuted(sender)) {
            return;
        }
        if (target.id().equals(sender.getUniqueId())) {
            messages.send(sender, "private.self");
            return;
        }
        if (ignoreService.ignores(target.id(), sender.getName())) {
            messages.send(sender, "private.player-ignored");
            return;
        }
        if (!sessions.isLocal(target.id()) && !settings.proxyEnabled()) {
            messages.send(sender, "private.player-not-found");
            return;
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            messages.send(sender, "private.usage");
            return;
        }
        Settings current = settings;
        if (rawMessage.length() > current.maxMessageLength()) {
            messages.send(sender, "chat.message-too-long");
            return;
        }
        String message = filters.filterChat(rawMessage);
        if (message.isBlank()) {
            return;
        }
        if (!tryRecordMessage(sender.getUniqueId(), message, current.antiRepeatWindowMillis(),
                current.antiRepeatSimilarity())) {
            messages.send(sender, "chat.repeated-message");
            return;
        }

        MessageRenderer.PrivateRenderedMessage rendered = renderer.renderPrivate(
                sender,
                current,
                message,
                current.privateSenderFormat(),
                current.privateReceiverFormat(),
                current.privateSpyFormat(),
                target.name(),
                false
        );
        Component senderComponent = rendered.senderComponent();
        Component receiverComponent = rendered.receiverComponent();
        Component spyComponent = rendered.spyComponent();
        UUID messageId = UUID.randomUUID();
        seenMessages.markIfNew(messageId);
        String senderName = sender.getName();
        replyTargets.put(sender.getUniqueId(), new ReplyTarget(target.id(), target.name()));
        sessions.runAt(target.id(), scheduler, targetPlayer -> {
            targetPlayer.sendMessage(receiverComponent);
            functions.notifyMention(
                    targetPlayer,
                    sender.getUniqueId(),
                    sender.getName(),
                    rendered.mentionedPlayers(),
                    rendered.mentionAll());
            replyTargets.put(target.id(), new ReplyTarget(sender.getUniqueId(), senderName));
            scripts.triggerPrivateReceived(
                    targetPlayer, senderName, message, current.serverName(), current.privateChannel());
        });
        sender.sendMessage(senderComponent);
        sendPrivateSpyMessage(
                sender.getUniqueId(),
                target.id(),
                spyComponent
        );
        if (current.proxyEnabled()) {
            transport.send(sender, new PrivateMessagePacket(
                    messageId,
                    System.currentTimeMillis(),
                    current.serverName(),
                    sender.getUniqueId(),
                    senderName,
                    target.id(),
                    target.name(),
                    message,
                    GsonComponentSerializer.gson().serialize(spyComponent),
                    GsonComponentSerializer.gson().serialize(receiverComponent),
                    rendered.showcases()
            ));
        }
        scripts.triggerPrivateSent(
                sender, target.name(), message, current.serverName(), current.privateChannel());
    }

    private void sendPrivateSpyMessage(
            UUID senderId,
            UUID targetId,
            Component component
    ) {
        if (privateSpies.isEmpty()) {
            return;
        }
        for (UUID spyId : privateSpies) {
            if (spyId.equals(senderId) || spyId.equals(targetId)) {
                continue;
            }
            sessions.runAt(spyId, scheduler, spy -> {
                if (spy.isOnline()) {
                    spy.sendMessage(component);
                }
            });
        }
    }

    private PlayerSnapshot snapshot(Player player) {
        Location location = player.getLocation();
        return new PlayerSnapshot(
                player.getUniqueId(),
                player.getName(),
                location.getWorld().getUID(),
                location.getX(),
                location.getY(),
                location.getZ()
        );
    }

    private boolean tryAcquireCooldown(UUID playerId, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        boolean[] accepted = {false};
        cooldowns.compute(playerId, (ignored, last) -> {
            if (last == null || now - last >= cooldownMillis) {
                accepted[0] = true;
                return now;
            }
            return last;
        });
        return accepted[0];
    }

    private boolean tryRecordMessage(UUID playerId, String message, long windowMillis, double similarityThreshold) {
        long now = System.currentTimeMillis();
        String normalized = normalizeMessage(message);
        boolean[] accepted = {false};
        lastMessages.compute(playerId, (ignored, previous) -> {
            if (windowMillis > 0 && previous != null && now - previous.sentAt() < windowMillis
                    && similarityThreshold > 0D && similarity(previous.message(), normalized) >= similarityThreshold) {
                return previous;
            }
            accepted[0] = true;
            return new LastMessage(normalized, now);
        });
        return accepted[0];
    }

    private static String normalizeMessage(String message) {
        return message.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static double similarity(String first, String second) {
        String normalizedFirst = removeSigns(first);
        String normalizedSecond = removeSigns(second);
        int longest = Math.max(normalizedFirst.length(), normalizedSecond.length());
        if (longest == 0) {
            return 1D;
        }
        int[] previous = new int[normalizedSecond.length() + 1];
        int[] current = new int[normalizedSecond.length() + 1];
        for (int row = 1; row <= normalizedFirst.length(); row++) {
            current[0] = 0;
            for (int column = 1; column <= normalizedSecond.length(); column++) {
                if (normalizedFirst.charAt(row - 1) == normalizedSecond.charAt(column - 1)) {
                    current[column] = previous[column - 1] + 1;
                } else {
                    current[column] = Math.max(previous[column], current[column - 1]);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return (double) previous[normalizedSecond.length()] / longest;
    }

    private static String removeSigns(String message) {
        StringBuilder result = new StringBuilder(message.length());
        for (int index = 0; index < message.length(); index++) {
            char character = message.charAt(index);
            if ((character >= 0x4E00 && character <= 0X9FA5)
                    || (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private boolean isMuted(Player player) {
        MuteRecord record = muteService.active(player.getUniqueId());
        if (record == null) {
            return false;
        }
        messages.send(player, "mute.muted", Map.of(
                "reason", record.reason(),
                "remaining", MuteService.remaining(record, System.currentTimeMillis())
        ));
        return true;
    }

    private record ReplyTarget(UUID id, String name) {
    }

    private record LastMessage(String message, long sentAt) {
    }
}
