package com.dirges.fxchat.bukkit;

import com.dirges.fxchat.bukkit.chat.ChatService;
import com.dirges.fxchat.bukkit.chat.ChatFilterService;
import com.dirges.fxchat.bukkit.chat.MentionCompletionService;
import com.dirges.fxchat.bukkit.command.FXChatCommand;
import com.dirges.fxchat.bukkit.config.CustomFunctionSettings;
import com.dirges.fxchat.bukkit.config.DatabaseSettings;
import com.dirges.fxchat.bukkit.config.FunctionSettings;
import com.dirges.fxchat.bukkit.config.MessageService;
import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.config.SettingsLoader;
import com.dirges.fxchat.bukkit.command.ConfigCommand;
import com.dirges.fxchat.bukkit.function.ChatFunctionService;
import com.dirges.fxchat.bukkit.function.ShowcaseStore;
import com.dirges.fxchat.bukkit.hook.CraftEngineHook;
import com.dirges.fxchat.bukkit.hook.BlockLockerHook;
import com.dirges.fxchat.bukkit.hook.CustomNameplatesHook;
import com.dirges.fxchat.bukkit.hook.PapiHook;
import com.dirges.fxchat.bukkit.hook.LandsHook;
import com.dirges.fxchat.bukkit.listener.FXChatListener;
import com.dirges.fxchat.bukkit.moderation.MuteRecord;
import com.dirges.fxchat.bukkit.moderation.MuteService;
import com.dirges.fxchat.bukkit.moderation.IgnoreService;
import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.proxy.BukkitProxyTransport;
import com.dirges.fxchat.bukkit.render.MessageRenderer;
import com.dirges.fxchat.bukkit.scheduler.FoliaSupport;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.dirges.fxchat.bukkit.script.ChatScriptService;
import com.dirges.fxchat.common.protocol.ChatPacket;
import com.dirges.fxchat.common.protocol.PrivateMessagePacket;
import com.dirges.fxchat.common.protocol.MutePacket;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class FXChatBukkit extends JavaPlugin {
    private SchedulerFacade scheduler;
    private SettingsLoader settingsLoader;
    private MessageService messages;
    private PlayerSessionManager sessions;
    private ChatFunctionService functions;
    private ChatScriptService scripts;
    private ChatService chatService;
    private ChatFilterService chatFilters;
    private MuteService muteService;
    private IgnoreService ignoreService;
    private MentionCompletionService mentionCompletions;
    private BukkitProxyTransport transport;
    private CustomNameplatesHook customNameplates;
    private FXChatCommand command;
    private CommandMap commandMap;
    private final List<ConfigCommand> configuredCommands = new ArrayList<>();
    private volatile Settings settings;

    @Override
    public void onEnable() {
        FoliaSupport.detect();
        scheduler = new SchedulerFacade(this);
        boolean dataFolderMissing = !getDataFolder().isDirectory();
        if (dataFolderMissing) {
            saveDefaultConfig();
            List.of(
                    "proxy.yml",
                    "database.yml",
                    "filters.yml",
                    "filters/local.txt",
                    "functions.yml",
                    "custom-functions.yml"
            ).forEach(path -> saveResource(path, false));
        }
        settingsLoader = new SettingsLoader(getDataFolder(), getServer().getName(), getLogger()::warning);
        saveDefaultDirectoryIfMissing("channels", List.of(
                "channels/公开.yml",
                "channels/附近.yml",
                "channels/私聊.yml"
        ));
        saveDefaultDirectoryIfMissing("scripts", List.of(
                "scripts/message-pickup.js",
                "scripts/private-message.js",
                "scripts/channel-switch.js"
        ));
        saveDefaultDirectoryIfMissing("lang", List.of(
                "lang/zh_CN.yml",
                "lang/en_US.yml"
        ));
        if (!new java.io.File(getDataFolder(), "filters/local.txt").isFile()) {
            saveResource("filters/local.txt", false);
        }

        settings = settingsLoader.load();
        chatFilters = new ChatFilterService(scheduler, new java.io.File(getDataFolder(), "filters.yml"), getLogger()::warning);
        chatFilters.update(settings.filters());
        messages = new MessageService(getDataFolder(), getLogger()::warning);
        messages.reload(settings.language());

        PapiHook papi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") ? new PapiHook() : null;
        CraftEngineHook craftEngine = null;
        if (getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            try {
                craftEngine = new CraftEngineHook();
                getLogger().info("CraftEngine container compatibility enabled.");
            } catch (LinkageError | RuntimeException exception) {
                getLogger().warning("CraftEngine container compatibility is unavailable: "
                        + exception.getMessage());
            }
        }
        Consumer<Player> leaveExternalChat = player -> { };
        if (getServer().getPluginManager().isPluginEnabled("Lands")) {
            try {
                LandsHook lands = LandsHook.create(this);
                leaveExternalChat = lands::leaveChat;
                getLogger().info("Lands chat compatibility enabled.");
            } catch (LinkageError | RuntimeException exception) {
                getLogger().warning("Lands chat compatibility is unavailable: " + exception.getMessage());
            }
        }
        if (getServer().getPluginManager().isPluginEnabled("CustomNameplates")) {
            try {
                customNameplates = CustomNameplatesHook.create();
                getLogger().info("CustomNameplates chat compatibility enabled; private bubbles are disabled.");
            } catch (LinkageError | RuntimeException exception) {
                getLogger().warning("CustomNameplates chat compatibility is unavailable: "
                        + exception.getMessage());
            }
        }
        BlockLockerHook blockLocker = null;
        if (getServer().getPluginManager().isPluginEnabled("BlockLocker")) {
            try {
                blockLocker = BlockLockerHook.create();
                getLogger().info("BlockLocker sign-filter compatibility enabled.");
            } catch (LinkageError | RuntimeException exception) {
                getLogger().warning("BlockLocker sign-filter compatibility is unavailable: "
                        + exception.getMessage());
            }
        }
        sessions = new PlayerSessionManager();
        muteService = new MuteService(
                getDataFolder(),
                DatabaseSettings.load(new java.io.File(getDataFolder(), "database.yml"), getLogger()::warning),
                scheduler,
                getLogger()::warning
        );
        muteService.start();
        ignoreService = new IgnoreService(getDataFolder(), scheduler, getLogger()::warning);
        FunctionSettings functionSettings = FunctionSettings.load(
                new java.io.File(getDataFolder(), "functions.yml"), getLogger()::warning);
        mentionCompletions = new MentionCompletionService(
                scheduler,
                sessions,
                functionSettings.mentionAll().enabled(),
                functionSettings.mentionAll().keys()
        );
        ShowcaseStore showcases = new ShowcaseStore();
        functions = new ChatFunctionService(
                scheduler,
                sessions,
                messages,
                showcases,
                craftEngine,
                papi,
                functionSettings,
                CustomFunctionSettings.load(
                        new java.io.File(getDataFolder(), "custom-functions.yml"), getLogger()::warning)
        );
        scripts = new ChatScriptService(
                scheduler,
                messages,
                ChatScriptService.load(new java.io.File(getDataFolder(), "scripts"), getLogger()::warning),
                getLogger()::warning
        );
        MessageRenderer renderer = new MessageRenderer(papi, functions);

        AtomicReference<ChatService> serviceReference = new AtomicReference<>();
        transport = new BukkitProxyTransport(this, packet -> {
            ChatService service = serviceReference.get();
            if (service == null) {
                return;
            }
            if (packet instanceof ChatPacket chatPacket) {
                service.receiveRemote(chatPacket);
            } else if (packet instanceof PrivateMessagePacket privatePacket) {
                service.receivePrivateRemote(privatePacket);
            } else if (packet instanceof MutePacket mutePacket) {
                muteService.applyRemote(new MuteRecord(
                        mutePacket.playerId(),
                        mutePacket.playerName(),
                        mutePacket.reason(),
                        mutePacket.mutedBy(),
                        mutePacket.mutedAt(),
                        mutePacket.expiresAt()
                ));
            }
        }, directory -> {
            sessions.updateRemoteNames(directory.players());
            mentionCompletions.refresh();
        });
        chatService = new ChatService(
                this, scheduler, messages, settings, sessions, renderer, functions, scripts, transport,
                leaveExternalChat, customNameplates, muteService, ignoreService, chatFilters);
        serviceReference.set(chatService);
        transport.enable();

        commandMap = getServer().getCommandMap();
        command = new FXChatCommand(this, scheduler, sessions, messages, functions, chatService,
                muteService, ignoreService, transport);
        registerConfiguredCommands(command, settings);

        scheduler.runGlobal(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                scheduler.runAtEntity(player, () -> {
                    sessions.join(player);
                    mentionCompletions.refreshFor(player.getUniqueId());
                });
            }
        });
        Bukkit.getPluginManager().registerEvents(
                new FXChatListener(scheduler, chatService, chatFilters, sessions, mentionCompletions, blockLocker,
                        ignoreService, renderer), this);
        if (getCommand("fxchat") != null) {
            Objects.requireNonNull(getCommand("fxchat")).setExecutor(command);
            Objects.requireNonNull(getCommand("fxchat")).setTabCompleter(command);
            registerViewCommand("view-item", command);
            registerViewCommand("view-inventory", command);
            registerViewCommand("view-enderchest", command);
            registerViewCommand("view-container", command);
        }
        getLogger().info("FXChat enabled on " + (FoliaSupport.isFolia() ? "Folia" : "Paper") + ".");
    }

    private void saveDefaultDirectoryIfMissing(String name, List<String> resources) {
        java.io.File directory = new java.io.File(getDataFolder(), name);
        if (directory.isDirectory()) {
            return;
        }
        resources.forEach(path -> saveResource(path, false));
    }

    public Settings settings() {
        return settings;
    }

    public void reloadSettings(UUID replyPlayerId) {
        scheduler.runAsync(() -> {
            try {
                Settings nextSettings = settingsLoader.load();
                FunctionSettings nextFunctions = FunctionSettings.load(
                        new java.io.File(getDataFolder(), "functions.yml"), getLogger()::warning);
                CustomFunctionSettings nextCustomFunctions = CustomFunctionSettings.load(
                        new java.io.File(getDataFolder(), "custom-functions.yml"), getLogger()::warning);
                java.util.List<ChatScriptService.ScriptFile> nextScripts = ChatScriptService.load(
                        new java.io.File(getDataFolder(), "scripts"), getLogger()::warning);
                messages.reload(nextSettings.language());
                scheduler.runGlobal(() -> {
                    settings = nextSettings;
                    functions.updateSettings(nextFunctions);
                    functions.updateCustomFunctions(nextCustomFunctions);
                    mentionCompletions.updateMentionAll(
                            nextFunctions.mentionAll().enabled(), nextFunctions.mentionAll().keys());
                    scripts.update(nextScripts);
                    chatFilters.update(nextSettings.filters());
                    chatService.updateSettings(nextSettings);
                    registerConfiguredCommands(command, nextSettings);
                    sendReloadResult(replyPlayerId, "command.reload-success");
                });
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Could not reload FXChat configuration", exception);
                scheduler.runGlobal(() -> sendReloadResult(replyPlayerId, "command.reload-failed"));
            }
        });
    }

    private void sendReloadResult(UUID playerId, String messageKey) {
        if (playerId == null) {
            messages.send(getServer().getConsoleSender(), messageKey);
            return;
        }
        sessions.runAt(playerId, scheduler, player -> messages.send(player, messageKey));
    }

    @Override
    public void onDisable() {
        unregisterConfiguredCommands();
        if (chatService != null) {
            chatService.close();
        }
        if (chatFilters != null) {
            chatFilters.close();
        }
        if (muteService != null) {
            muteService.close();
        }
        if (ignoreService != null) {
            ignoreService.close();
        }
        if (customNameplates != null) {
            customNameplates.close();
            customNameplates = null;
        }
        if (functions != null) {
            functions.close();
        }
        if (scripts != null) {
            scripts.close();
        }
        if (mentionCompletions != null) {
            mentionCompletions.close();
        }
        if (transport != null) {
            transport.close();
        }
        if (sessions != null) {
            sessions.clear();
        }
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private void registerViewCommand(String name, FXChatCommand command) {
        if (getCommand(name) != null) {
            Objects.requireNonNull(getCommand(name)).setExecutor((sender, command1, label, args) -> command.onViewCommand(sender, command1, args));
            Objects.requireNonNull(getCommand(name)).setTabCompleter(command);
        }
    }

    private void registerConfiguredCommands(FXChatCommand handler, Settings current) {
        unregisterConfiguredCommands();
        if (commandMap == null) {
            return;
        }
        Set<String> labels = new HashSet<>();
        for (Settings.ChannelSettings channel : current.channels().values()) {
            String description = "Send a message to the " + channel.id() + " chat channel";
            String commandName = normalizeCommandName(channel.id());
            if (commandName != null) {
                registerConfiguredCommand(
                        handler, labels, commandName, channel.aliases(), description,
                        "/" + commandName + " [message]", ConfigCommand.Kind.CHANNEL);
            } else {
                for (String alias : channel.aliases()) {
                    registerConfiguredCommand(
                            handler, labels, alias, List.of(), description,
                            "/" + alias + " [message]", ConfigCommand.Kind.CHANNEL);
                }
            }
        }
        Settings.PrivateCommandSettings privateCommands = current.privateCommands();
        registerConfiguredCommand(
                handler,
                labels,
                privateCommands.message().name(),
                privateCommands.message().aliases(),
                "Send a private message",
                privateCommands.message().usage(),
                ConfigCommand.Kind.PRIVATE
        );
        registerConfiguredCommand(
                handler,
                labels,
                privateCommands.reply().name(),
                privateCommands.reply().aliases(),
                "Reply to the last private message",
                privateCommands.reply().usage(),
                ConfigCommand.Kind.REPLY
        );
        registerConfiguredCommand(
                handler,
                labels,
                "mute",
                List.of(),
                "Mute a player",
                "/mute <player> <reason> <duration>",
                ConfigCommand.Kind.MUTE
        );
    }

    private void registerConfiguredCommand(
            FXChatCommand handler,
            Set<String> labels,
            String rawName,
            List<String> rawAliases,
            String description,
            String usage,
            ConfigCommand.Kind kind
    ) {
        String name = normalizeCommandName(rawName);
        if (name == null || !labels.add(name)) {
            getLogger().warning("Ignored invalid or duplicate configured command: " + rawName);
            return;
        }
        List<String> aliases = new ArrayList<>();
        for (String rawAlias : rawAliases) {
            String alias = normalizeCommandName(rawAlias);
            if (alias != null && !alias.equals(name) && labels.add(alias)) {
                aliases.add(alias);
            }
        }
        ConfigCommand command = new ConfigCommand(name, handler, kind);
        command.setDescription(description);
        command.setUsage(usage);
        command.setAliases(aliases);
        boolean registered = commandMap.register(getName().toLowerCase(Locale.ROOT), command);
        if (commandMap.getCommand(name) != command) {
            getLogger().warning("Could not register configured command /" + name + ".");
            command.unregister(commandMap);
            return;
        }
        if (!registered) {
            getLogger().warning("Configured command /" + name
                    + " registered without one or more aliases.");
        }
        configuredCommands.add(command);
    }

    private void unregisterConfiguredCommands() {
        if (commandMap == null || configuredCommands.isEmpty()) {
            return;
        }
        for (ConfigCommand command : configuredCommands) {
            command.unregister(commandMap);
            removeKnownCommand(command);
        }
        configuredCommands.clear();
    }

    private void removeKnownCommand(ConfigCommand command) {
        if (!(commandMap instanceof SimpleCommandMap simpleCommandMap)) {
            return;
        }
        List<String> keys = simpleCommandMap.getKnownCommands().entrySet().stream()
                .filter(entry -> entry.getValue() == command)
                .map(Map.Entry::getKey)
                .toList();
        Map<String, org.bukkit.command.Command> knownCommands = simpleCommandMap.getKnownCommands();
        keys.forEach(knownCommands::remove);
    }

    private static String normalizeCommandName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        return name.matches("[a-z0-9._-]+") ? name : null;
    }
}
