package com.dirges.fxchat.bukkit.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record Settings(
        String serverName,
        boolean proxyEnabled,
        long cooldownMillis,
        long antiRepeatWindowMillis,
        double antiRepeatSimilarity,
        int maxMessageLength,
        String defaultChannel,
        String privateChannel,
        String globalPrefix,
        String prefixChannel,
        List<FilterRule> filters,
        Map<String, ChannelSettings> channels,
        String privateSenderFormat,
        String privateReceiverFormat,
        String privateSpyFormat,
        PrivateCommandSettings privateCommands,
        String language
) {
    public Settings {
        filters = List.copyOf(filters);
        channels = Map.copyOf(channels);
    }

    public ChannelSettings channel(String id) {
        if (id == null) {
            return null;
        }
        return channels.get(id.toLowerCase(Locale.ROOT));
    }

    public String resolveChannel(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        ChannelSettings direct = channels.get(normalized);
        if (direct != null) {
            return direct.id();
        }
        for (ChannelSettings channel : channels.values()) {
            if (channel.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(normalized))) {
                return channel.id();
            }
        }
        return null;
    }

    public record ChannelSettings(
            String id,
            String permission,
            double range,
            boolean crossServer,
            List<String> aliases,
            String format,
            String globalPrefix,
            String prefixChannel
    ) {
        public ChannelSettings {
            aliases = List.copyOf(aliases);
        }
    }

    public record FilterRule(Pattern pattern, String replacement) {
        public String replacementFor(String matchedText) {
            return replacement.codePointCount(0, replacement.length()) == 1
                    ? replacement.repeat(matchedText.codePointCount(0, matchedText.length()))
                    : replacement;
        }
    }

    public record PrivateCommandSettings(CommandSettings message, CommandSettings reply) {
    }

    public record CommandSettings(String name, List<String> aliases, String usage) {
        public CommandSettings {
            name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
            usage = usage == null ? "" : usage;
        }
    }
}
