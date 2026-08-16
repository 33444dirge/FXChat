package com.dirges.fxchat.bukkit.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public record FunctionSettings(
        Mention mention,
        MentionAll mentionAll,
        Showcase itemShow,
        Showcase inventoryShow,
        Showcase enderChestShow,
        Showcase containerShow
) {
    public static FunctionSettings load(File file, Consumer<String> warning) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        return new FunctionSettings(
                new Mention(
                        config.getBoolean("mention.enabled", true),
                        config.getString("mention.permission", ""),
                        config.getBoolean("mention.notify", true),
                        config.getBoolean("mention.self-mention", false),
                        parseMillis(config.getString("mention.cooldown", "30s"), 30_000L, warning, "mention.cooldown"),
                        config.getString("mention.pattern", "@(?<name>(names))")
                ),
                new MentionAll(
                        config.getBoolean("mention-all.enabled", true),
                        config.getString("mention-all.permission", "fxchat.function.mentionall"),
                        config.getBoolean("mention-all.notify", true),
                        parseMillis(config.getString("mention-all.cooldown", "5m"), 300_000L, warning, "mention-all.cooldown"),
                        list(config, "mention-all.keys", List.of("@all", "@everyone", "@everybody", "@所有人", "@全体成员"))
                ),
                readShowcase(config, warning, "item-show", "",
                        List.of("%i%", "%i", "%item%", "%item", "[i]", "[item]"), 1),
                readShowcase(config, warning, "inventory-show", "fxchat.function.inventoryshow",
                        List.of("[inv]", "[inventory]"), 1),
                readShowcase(config, warning, "enderchest-show", "fxchat.function.enderchestshow",
                        List.of("[ender]", "[enderchest]"), 1),
                readShowcase(config, warning, "container-show", "fxchat.function.containershow",
                        List.of("[cc]"), 6)
        );
    }

    private static Showcase readShowcase(
            FileConfiguration config,
            Consumer<String> warning,
            String path,
            String defaultPermission,
            List<String> defaultKeys,
            int defaultRange
    ) {
        return new Showcase(
                config.getBoolean(path + ".enabled", true),
                config.getString(path + ".permission", defaultPermission),
                parseMillis(config.getString(path + ".cooldown", Long.toString(30000L)), 30000L,
                        warning, path + ".cooldown"),
                list(config, path + ".keys", defaultKeys),
                config.getBoolean(path + ".ui", true),
                config.getBoolean(path + ".compatible", false),
                Math.clamp(config.getInt(path + ".range", defaultRange), 1, 32)
        );
    }

    private static List<String> list(FileConfiguration config, String path, List<String> fallback) {
        List<String> result = config.getStringList(path);
        return result.isEmpty() ? List.copyOf(fallback) : List.copyOf(result);
    }

    private static long parseMillis(String raw, long fallback, Consumer<String> warning, String path) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        long multiplier = 1L;
        if (value.endsWith("ms")) {
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("s")) {
            multiplier = 1_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 60_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("h")) {
            multiplier = 3_600_000L;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Math.clamp(Math.multiplyExact(Long.parseLong(value), multiplier), 0L, 86_400_000L);
        } catch (RuntimeException exception) {
            warning.accept("Invalid duration in " + path + ": " + raw + "; using " + fallback + "ms.");
            return fallback;
        }
    }

    public record Mention(
            boolean enabled,
            String permission,
            boolean notifyEnabled,
            boolean selfMention,
            long cooldownMillis,
            String pattern
    ) {
    }

    public record MentionAll(
            boolean enabled,
            String permission,
            boolean notifyEnabled,
            long cooldownMillis,
            List<String> keys
    ) {
        public MentionAll {
            keys = List.copyOf(keys);
        }
    }

    public record Showcase(
            boolean enabled,
            String permission,
            long cooldownMillis,
            List<String> keys,
            boolean ui,
            boolean compatible,
            int range
    ) {
        public Showcase {
            keys = List.copyOf(keys);
        }

        public boolean matches(String message) {
            return keys.stream().noneMatch(key -> !key.isBlank() && message.toLowerCase(java.util.Locale.ROOT)
                    .contains(key.toLowerCase(java.util.Locale.ROOT)));
        }
    }
}
