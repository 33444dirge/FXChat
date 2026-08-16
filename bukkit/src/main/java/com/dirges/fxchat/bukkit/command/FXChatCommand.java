package com.dirges.fxchat.bukkit.command;

import com.dirges.fxchat.bukkit.FXChatBukkit;
import com.dirges.fxchat.bukkit.chat.ChatService;
import com.dirges.fxchat.bukkit.config.MessageService;
import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.function.ChatFunctionService;
import com.dirges.fxchat.bukkit.function.ShowcaseStore;
import com.dirges.fxchat.bukkit.moderation.MuteRecord;
import com.dirges.fxchat.bukkit.moderation.MuteService;
import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.proxy.BukkitProxyTransport;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.dirges.fxchat.common.protocol.MutePacket;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class FXChatCommand implements CommandExecutor, TabCompleter {
    private final FXChatBukkit plugin;
    private final SchedulerFacade scheduler;
    private final PlayerSessionManager sessions;
    private final MessageService messages;
    private final ChatFunctionService functions;
    private final ChatService chatService;
    private final MuteService muteService;
    private final BukkitProxyTransport transport;

    public FXChatCommand(
            FXChatBukkit plugin,
            SchedulerFacade scheduler,
            PlayerSessionManager sessions,
            MessageService messages,
            ChatFunctionService functions,
            ChatService chatService,
            MuteService muteService,
            BukkitProxyTransport transport
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.sessions = sessions;
        this.messages = messages;
        this.functions = functions;
        this.chatService = chatService;
        this.muteService = muteService;
        this.transport = transport;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "command.help");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> messages.send(sender, "command.help");
            case "version" -> messages.send(sender, "command.version", Map.of(
                    "version", plugin.getDescription().getVersion()
            ));
            case "reload" -> {
                if (!sender.hasPermission("fxchat.admin")) {
                    messages.send(sender, "command.no-permission");
                    return true;
                }
                messages.send(sender, "command.reload-started");
                plugin.reloadSettings(sender instanceof Player player ? player.getUniqueId() : null);
            }
            case "channel" -> selectChannel(sender, args);
            case "sudo" -> sudo(sender, args);
            case "view" -> view(sender, args);
            case "spy" -> privateSpy(sender, args);
            default -> messages.send(sender, "command.unknown");
        }
        return true;
    }

    public boolean onViewCommand(CommandSender sender, Command command, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.only-player");
            return true;
        }
        if (args.length < 1) {
            messages.send(sender, "command.view-usage");
            return true;
        }
        ShowcaseStore.Kind kind = switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "view-item" -> ShowcaseStore.Kind.ITEM;
            case "view-inventory" -> ShowcaseStore.Kind.INVENTORY;
            case "view-enderchest" -> ShowcaseStore.Kind.ENDER_CHEST;
            case "view-container" -> ShowcaseStore.Kind.CONTAINER;
            default -> null;
        };
        if (kind == null) {
            messages.send(sender, "command.unknown");
            return true;
        }
        functions.openShowcase(player, kind, args[0]);
        return true;
    }

    public boolean onChannelCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.only-player");
            return true;
        }
        String channel = chatService.channelAlias(command.getName());
        if (channel == null) {
            channel = chatService.channelAlias(label);
        }
        if (channel == null) {
            messages.send(sender, "command.channel-not-found");
            return true;
        }
        chatService.handleChannelAlias(player, channel, String.join(" ", args));
        return true;
    }

    public boolean onPrivateCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "private.only-player");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "private.player-required");
            return true;
        }
        if (args.length == 1) {
            chatService.enterPrivateChannel(player, args[0]);
            return true;
        }
        chatService.sendPrivateMessage(
                player,
                args[0],
                String.join(" ", Arrays.copyOfRange(args, 1, args.length))
        );
        return true;
    }

    public boolean onReplyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "private.only-player");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "private.reply-usage");
            return true;
        }
        chatService.replyPrivateMessage(player, String.join(" ", args));
        return true;
    }

    private void privateSpy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "private.spy-only-player");
            return;
        }
        if (!sender.hasPermission("fxchat.spy")) {
            messages.send(sender, "private.spy-no-permission");
            return;
        }
        if (args.length > 2) {
            messages.send(sender, "private.spy-usage");
            return;
        }
        Boolean requested = null;
        if (args.length == 2) {
            requested = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "on", "enable", "enabled", "true" -> true;
                case "off", "disable", "disabled", "false" -> false;
                default -> null;
            };
            if (requested == null) {
                messages.send(sender, "private.spy-usage");
                return;
            }
        }
        boolean enabled = requested == null
                ? chatService.togglePrivateSpy(player.getUniqueId())
                : chatService.setPrivateSpy(player.getUniqueId(), requested);
        messages.send(sender, enabled ? "private.spy-enabled" : "private.spy-disabled");
    }

    public boolean onMuteCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fxchat.mute")) {
            messages.send(sender, "mute.no-permission");
            return true;
        }
        if (args.length < 3) {
            messages.send(sender, "mute.usage");
            return true;
        }
        PlayerSessionManager.OnlinePlayer target = sessions.onlineNameIndex()
                .get(args[0].toLowerCase(Locale.ROOT));
        if (target == null) {
            messages.send(sender, "mute.player-not-found");
            return true;
        }
        if (sender instanceof Player player && target.id().equals(player.getUniqueId())) {
            messages.send(sender, "mute.self");
            return true;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)).trim();
        if (reason.isBlank()) {
            messages.send(sender, "mute.usage");
            return true;
        }
        if (reason.length() > 512) {
            messages.send(sender, "mute.reason-too-long");
            return true;
        }
        long now = System.currentTimeMillis();
        MuteService.DurationSpec duration = MuteService.parseDuration(args[args.length - 1], now);
        if (duration == null) {
            messages.send(sender, "mute.invalid-duration");
            return true;
        }
        MuteRecord record = new MuteRecord(
                target.id(),
                target.name(),
                reason,
                sender.getName(),
                now,
                duration.expiresAt()
        );
        muteService.save(
                record,
                saved -> completeMute(sender, saved),
                ignored -> sendMuteResult(sender)
        );
        return true;
    }

    private void completeMute(CommandSender sender, MuteRecord record) {
        scheduler.runGlobal(() -> {
            Bukkit.getOnlinePlayers().stream().findFirst().ifPresent(carrier -> scheduler.runAtEntity(carrier, () -> transport.send(carrier, new MutePacket(
                    UUID.randomUUID(),
                    System.currentTimeMillis(),
                    plugin.settings().serverName(),
                    record.playerId(),
                    record.playerName(),
                    record.reason(),
                    record.mutedBy(),
                    record.mutedAt(),
                    record.expiresAt()
            ))));
            if (sender instanceof Player player) {
                scheduler.runAtEntity(player, () -> messages.send(player, "mute.success", Map.of(
                        "player", record.playerName(),
                        "reason", record.reason(),
                        "duration", MuteService.remaining(record, record.mutedAt())
                )));
            } else {
                messages.send(sender, "mute.success", Map.of(
                        "player", record.playerName(),
                        "reason", record.reason(),
                        "duration", MuteService.remaining(record, record.mutedAt())
                ));
            }
        });
    }

    private void sendMuteResult(CommandSender sender) {
        scheduler.runGlobal(() -> {
            if (sender instanceof Player player) {
                scheduler.runAtEntity(player, () -> messages.send(player, "mute.database-error"));
            } else {
                messages.send(sender, "mute.database-error");
            }
        });
    }

    private void sudo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fxchat.sudo")) {
            messages.send(sender, "command.no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.sudo-only-player");
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "command.sudo-usage");
            return;
        }
        Settings current = plugin.settings();
        String requestedChannel;
        String privateTargetName = null;
        int messageEnd = args.length;

        if (args.length >= 4) {
            String penultimateChannel = current.resolveChannel(args[args.length - 2]);
            if (current.privateChannel().equals(penultimateChannel)) {
                requestedChannel = penultimateChannel;
                privateTargetName = args[args.length - 1];
                messageEnd -= 2;
            } else {
                requestedChannel = current.resolveChannel(args[args.length - 1]);
                if (requestedChannel != null) {
                    messageEnd--;
                }
            }
        } else {
            requestedChannel = current.resolveChannel(args[args.length - 1]);
            if (requestedChannel != null) {
                messageEnd--;
            }
        }

        if (messageEnd <= 2) {
            messages.send(sender, "command.sudo-usage");
            return;
        }
        if (current.privateChannel().equals(requestedChannel)
                && (privateTargetName == null || privateTargetName.isBlank())) {
            messages.send(sender, "command.sudo-private-target-required");
            return;
        }
        chatService.handleSudo(
                player,
                args[1],
                String.join(" ", Arrays.copyOfRange(args, 2, messageEnd)),
                requestedChannel,
                privateTargetName
        );
    }

    private void selectChannel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.only-player");
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "command.channels", Map.of(
                    "channels", String.join(", ", plugin.settings().channels().keySet())
            ));
            return;
        }
        String channel = plugin.settings().resolveChannel(args[1]);
        if (channel == null) {
            messages.send(sender, "command.channel-not-found");
            return;
        }
        Settings.ChannelSettings settings = plugin.settings().channel(channel);
        if (!settings.permission().isBlank() && !player.hasPermission(settings.permission())) {
            messages.send(sender, "command.channel-no-permission");
            return;
        }
        if (settings.id().equals(plugin.settings().privateChannel())) {
            if (args.length < 3 || args[2].isBlank()) {
                messages.send(sender, "private.channel-usage");
                return;
            }
            chatService.enterPrivateChannel(player, args[2]);
            return;
        }
        boolean selected = chatService.selectChannel(player, channel);
        messages.send(sender, selected
                ? "command.channel-selected" : "command.channel-already-selected",
                Map.of("channel", chatService.activeChannel(player)));
    }

    private void view(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.only-player");
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "command.view-usage");
            return;
        }
        ShowcaseStore.Kind kind = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "item" -> ShowcaseStore.Kind.ITEM;
            case "inventory" -> ShowcaseStore.Kind.INVENTORY;
            case "enderchest", "ender" -> ShowcaseStore.Kind.ENDER_CHEST;
            case "container", "chest", "barrel" -> ShowcaseStore.Kind.CONTAINER;
            default -> null;
        };
        if (kind == null) {
            messages.send(sender, "command.view-usage");
            return;
        }
        functions.openShowcase(player, kind, args[2]);
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, Command command, @NonNull String alias, String @NonNull [] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("mute")) {
            return onMuteTabComplete(args);
        }
        if (commandName.equals("msg") || commandName.equals("tell") || commandName.equals("w")) {
            return onPrivateTabComplete(args, false);
        }
        if (commandName.equals("reply") || commandName.equals("r")
                || chatService.channelAlias(commandName) != null
                || chatService.channelAlias(alias) != null) {
            return List.of();
        }
        if (!commandName.equals("fxchat") && !commandName.equals("fxc")) {
            return List.of();
        }
        if (args.length == 1) {
            return Stream.of("channel", "help", "reload", "version", "view", "spy", "sudo")
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sudo")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return sessions.onlineNameIndex().values().stream()
                    .filter(value -> sessions.isLocal(value.id()))
                    .map(PlayerSessionManager.OnlinePlayer::name)
                    .distinct()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("sudo")) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return plugin.settings().channels().keySet().stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length >= 5 && args[0].equalsIgnoreCase("sudo")
                && plugin.settings().privateChannel().equals(
                plugin.settings().resolveChannel(args[args.length - 2]))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return sessions.onlineNameIndex().values().stream()
                    .map(PlayerSessionManager.OnlinePlayer::name)
                    .distinct()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spy")) {
            return Stream.of("on", "off")
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            return Stream.of("item", "inventory", "enderchest", "container")
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("channel")) {
            List<String> channels = new ArrayList<>(plugin.settings().channels().keySet());
            return channels.stream().filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("channel")
                && plugin.settings().privateChannel().equals(
                plugin.settings().resolveChannel(args[1]))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return sessions.onlineNameIndex().values().stream()
                    .map(PlayerSessionManager.OnlinePlayer::name)
                    .distinct()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    public List<String> onMuteTabComplete(
            String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return sessions.onlineNameIndex().values().stream()
                .map(PlayerSessionManager.OnlinePlayer::name)
                .distinct()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    public List<String> onPrivateTabComplete(
            String[] args,
            boolean reply
    ) {
        if (reply || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return sessions.onlineNameIndex().values().stream()
                .map(PlayerSessionManager.OnlinePlayer::name)
                .distinct()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
