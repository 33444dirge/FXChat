package com.dirges.fxchat.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SettingsLoader {
    private final File dataFolder;
    private final String fallbackServerName;
    private final Consumer<String> warning;

    public SettingsLoader(File dataFolder, String fallbackServerName, Consumer<String> warning) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.fallbackServerName = fallbackServerName;
        this.warning = warning;
    }

    public Settings load() {
        FileConfiguration config = loadFile("config.yml");
        FileConfiguration proxy = loadFile("proxy.yml");
        FileConfiguration filtersFile = loadFile("filters.yml");

        String language = config.getString("language", "zh_CN").trim();
        if (language.isEmpty()) {
            language = "zh_CN";
        }

        String serverName = proxy.getString("server-name", config.getString("proxy.server-name", "")).trim();
        if (serverName.isEmpty()) {
            serverName = fallbackServerName;
        }

        long cooldown = Math.clamp(config.getLong("chat.cooldown-ms", 750L), 0L, 60_000L);
        long antiRepeatWindow = Math.clamp(config.getLong("chat.anti-repeat-window-ms", 30_000L), 0L, 300_000L);
        double antiRepeatSimilarity = Math.clamp(config.getDouble("chat.anti-repeat-similarity", 0.85D), 0D, 1D);
        int maxLength = (int) Math.clamp(config.getLong("chat.max-length", 256L), 1L, 4096L);
        String prefix = config.getString("chat.global-prefix", "");
        String prefixChannel = config.getString("chat.prefix-channel", "公开");

        Map<String, Settings.ChannelSettings> channels = readChannels(config);
        if (channels.isEmpty()) {
            channels.put("公开", new Settings.ChannelSettings(
                "公开", "", 0D, false, List.of("g"),
                    "<gray>[公开]</gray> <white>{player}</white><gray>: </gray>{message}",
                    "", ""
            ));
        }
        for (Settings.ChannelSettings channel : channels.values()) {
            if (channel.globalPrefix() != null && !channel.globalPrefix().isBlank()) {
                prefix = channel.globalPrefix();
                prefixChannel = channel.prefixChannel() == null || channel.prefixChannel().isBlank()
                        ? channel.id() : channel.prefixChannel().toLowerCase(Locale.ROOT);
                break;
            }
        }
        String privateChannel = config.getString("private-channel",
                config.getString("chat.private-channel", "私聊"))
                .trim().toLowerCase(Locale.ROOT);
        if (privateChannel.isEmpty()) {
            privateChannel = "私聊";
        }
        String defaultChannel = config.getString("default-channel",
                config.getString("chat.default-channel", "公开"))
                .trim().toLowerCase(Locale.ROOT);
        if (!channels.containsKey(defaultChannel) || defaultChannel.equals(privateChannel)) {
            for (String channelId : channels.keySet()) {
                if (!channelId.equals(privateChannel)) {
                    defaultChannel = channelId;
                    break;
                }
            }
        }

        FileConfiguration privateChat = loadFile("channels/" + privateChannel + ".yml");
        List<Settings.FilterRule> filters = readFilters(filtersFile, config);
        Settings.PrivateCommandSettings privateCommands = readPrivateCommands(privateChat);
        return new Settings(
                serverName,
                proxy.getBoolean("enabled", config.getBoolean("proxy.enabled", true)),
                cooldown,
                antiRepeatWindow,
                antiRepeatSimilarity,
                maxLength,
                defaultChannel,
                privateChannel,
                prefix,
                prefixChannel,
                filters,
                channels,
                privateChat.getString("sender-format",
                        "<light_purple>[私聊]</light_purple> <white>{player}</white><gray>: </gray>{message}"),
                privateChat.getString("receiver-format",
                        "<light_purple>[私聊]</light_purple> <white>{player}</white><gray>: </gray>{message}"),
                privateChat.getString("spy-format",
                        "<dark_gray>[监听]</dark_gray> <gold>{sender}</gold> <green>-></green> "
                                + "<aqua>{target}</aqua><gray>: </gray>{message}"),
                privateCommands,
                language
        );
    }

    private Settings.PrivateCommandSettings readPrivateCommands(FileConfiguration config) {
        return new Settings.PrivateCommandSettings(
                readCommand(config, "commands.message", "msg", List.of("tell", "w"),
                        "/msg <player> <message>"),
                readCommand(config, "commands.reply", "reply", List.of("r"),
                        "/reply <message>")
        );
    }

    private Settings.CommandSettings readCommand(
            FileConfiguration config,
            String path,
            String defaultName,
            List<String> defaultAliases,
            String defaultUsage
    ) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return new Settings.CommandSettings(defaultName, defaultAliases, defaultUsage);
        }
        return new Settings.CommandSettings(
                section.getString("name", defaultName),
                section.contains("aliases") ? section.getStringList("aliases") : defaultAliases,
                section.getString("usage", defaultUsage)
        );
    }

    private FileConfiguration loadFile(String name) {
        return YamlConfiguration.loadConfiguration(new File(dataFolder, name));
    }

    private Map<String, Settings.ChannelSettings> readChannels(FileConfiguration config) {
        Map<String, Settings.ChannelSettings> directoryChannels = readChannelDirectory();
        if (!directoryChannels.isEmpty()) {
            return directoryChannels;
        }
        return readChannels(config.getConfigurationSection("channels"));
    }

    private Map<String, Settings.ChannelSettings> readChannelDirectory() {
        Map<String, Settings.ChannelSettings> result = new LinkedHashMap<>();
        File directory = new File(dataFolder, "channels");
        File[] files = directory.listFiles(file -> file.isFile() && isYaml(file));
        if (files == null) {
            return result;
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            try {
                FileConfiguration channel = YamlConfiguration.loadConfiguration(file);
                String id = channel.getString("id", stripExtension(file.getName()));
                ConfigurationSection values = channel.getConfigurationSection("channel");
                addChannel(result, id, values == null ? channel : values);
            } catch (RuntimeException exception) {
                warning.accept("Ignored invalid channel file " + file.getName() + ": " + exception.getMessage());
            }
        }
        return result;
    }

    private Map<String, Settings.ChannelSettings> readChannels(ConfigurationSection section) {
        Map<String, Settings.ChannelSettings> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String rawId : section.getKeys(false)) {
            ConfigurationSection channel = section.getConfigurationSection(rawId);
            if (channel != null) {
                addChannel(result, rawId, channel);
            }
        }
        return result;
    }

    private void addChannel(
            Map<String, Settings.ChannelSettings> result,
            String rawId,
            ConfigurationSection section
    ) {
        String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            warning.accept("Ignored channel with an empty id.");
            return;
        }
        result.put(id, new Settings.ChannelSettings(
                id,
                section.getString("permission", ""),
                section.getDouble("range", 0D),
                section.getBoolean("cross-server", false),
                new ArrayList<>(section.getStringList("aliases")),
                section.getString("format", "{message}"),
                section.getString("global-prefix", section.getString("prefix", "")),
                section.getString("prefix-channel", "")
        ));
    }

    private static boolean isYaml(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private List<Settings.FilterRule> readFilters(FileConfiguration filtersFile, FileConfiguration legacyConfig) {
        boolean enabled = filtersFile.contains("enabled")
                ? filtersFile.getBoolean("enabled")
                : legacyConfig.getBoolean("filters.enabled", false);
        if (!enabled) {
            return List.of();
        }
        String replacement = filtersFile.getString(
                "replacement",
                legacyConfig.getString("filters.replacement", "***")
        );
        List<String> words = filtersFile.contains("words")
                ? filtersFile.getStringList("words")
                : legacyConfig.getStringList("filters.words");
        List<String> allWords = new ArrayList<>(words);
        for (String name : filtersFile.getStringList("local-files")) {
            if (name == null || name.isBlank()) {
                continue;
            }
            File localFile = new File(new File(dataFolder, "filters"), name);
            try {
                if (localFile.isFile()) {
                    Files.readAllLines(localFile.toPath(), StandardCharsets.UTF_8).stream()
                            .map(String::trim)
                            .filter(word -> !word.isBlank() && !word.startsWith("#"))
                            .forEach(allWords::add);
                } else {
                    warning.accept("Local chat filter file was not found: " + name);
                }
            } catch (IOException exception) {
                warning.accept("Could not read local chat filter " + name + ": " + exception.getMessage());
            }
        }
        List<Settings.FilterRule> result = new ArrayList<>();
        for (String word : allWords) {
            if (word.isBlank()) {
                continue;
            }
            try {
                result.add(new Settings.FilterRule(
                        Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                        replacement
                ));
            } catch (RuntimeException exception) {
                warning.accept("Ignored invalid chat filter: " + word);
            }
        }
        for (String regex : filtersFile.getStringList("regex-words")) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            try {
                result.add(new Settings.FilterRule(
                        Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                        replacement
                ));
            } catch (RuntimeException exception) {
                warning.accept("Ignored invalid chat filter regular expression: " + regex);
            }
        }
        return result;
    }
}
