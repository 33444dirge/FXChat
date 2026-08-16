package com.dirges.fxchat.bukkit.config;

import com.dirges.fxchat.bukkit.text.MessageColorParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public final class MessageService {
    private final File dataFolder;
    private final Consumer<String> warning;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile FileConfiguration messages = new YamlConfiguration();

    public MessageService(File dataFolder, Consumer<String> warning) {
        this.dataFolder = dataFolder;
        this.warning = warning;
    }

    public void reload(String language) {
        String safeLanguage = language.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeLanguage.isBlank()) {
            safeLanguage = "en_US";
        }
        File file = new File(new File(dataFolder, "lang"), safeLanguage + ".yml");
        if (!file.isFile()) {
            warning.accept("Language file was not found: " + file + "; using en_US.yml.");
            file = new File(new File(dataFolder, "lang"), "en_US.yml");
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, ?> replacements) {
        FileConfiguration configuration = messages;
        for (Action action : actions(configuration, key)) {
            dispatch(sender, action, replacements);
        }
    }

    public void sendActions(CommandSender sender, List<?> rawActions, Map<String, ?> replacements) {
        if (rawActions == null) {
            return;
        }
        for (Object rawAction : rawActions) {
            dispatch(sender, action(rawAction), replacements);
        }
    }

    public void sendActionBar(Player player, String key, Map<String, ?> replacements) {
        Object raw = messages.get(key);
        if (!(raw instanceof List<?>) && !(raw instanceof ConfigurationSection)) {
            player.sendActionBar(component(key, replacements));
            return;
        }
        send(player, key, replacements);
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, ?> replacements) {
        FileConfiguration configuration = messages;
        List<Action> actions = actions(configuration, key);
        for (Action action : actions) {
            if (isTextAction(action.type()) && hasText(action)) {
                return deserialize(actionText(action, replacements));
            }
        }
        for (Action action : actions) {
            if (action.type().equals("actionbar") && hasText(action)) {
                return deserialize(actionText(action, replacements));
            }
        }
        return Component.text(key);
    }

    public String text(String key, Map<String, ?> replacements) {
        for (Action action : actions(messages, key)) {
            if (hasText(action)) {
                return actionText(action, replacements);
            }
        }
        return key;
    }

    private void dispatch(CommandSender sender, Action action, Map<String, ?> replacements) {
        String type = action.type();
        if (isTextAction(type)) {
            sender.sendMessage(deserialize(actionText(action, replacements)));
            return;
        }
        if (!(sender instanceof Player player)) {
            return;
        }
        switch (type) {
            case "actionbar" -> player.sendActionBar(deserialize(actionText(action, replacements)));
            case "sound" -> playSound(player, action, replacements);
            case "title" -> showTitle(player, action, replacements);
            default -> warning.accept("Unknown language action type: " + type);
        }
    }

    private void playSound(Player player, Action action, Map<String, ?> replacements) {
        String name = actionValue(action, "sound", replacements).trim();
        if (name.isBlank()) {
            warning.accept("Language sound action is missing a sound name.");
            return;
        }
        float volume = decimal(action, "volume", replacements);
        float pitch = decimal(action, "pitch", replacements);
        String categoryName = actionValue(action, "category", replacements).trim();
        String soundKey = normalizeSoundName(name);
        try {
            if (categoryName.isBlank()) {
                player.playSound(player.getLocation(), soundKey, volume, pitch);
            } else {
                SoundCategory category = SoundCategory.valueOf(categoryName.toUpperCase(Locale.ROOT));
                player.playSound(player.getLocation(), soundKey, category, volume, pitch);
            }
        } catch (IllegalArgumentException exception) {
            warning.accept("Unknown sound or category: " + name + " / " + categoryName);
        }
    }

    private static String normalizeSoundName(String name) {
        String value = name.trim();
        if (value.indexOf(':') >= 0) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            return Sound.valueOf(value.toUpperCase(Locale.ROOT)).getKey().toString();
        } catch (IllegalArgumentException ignored) {
            return "minecraft:" + value.toLowerCase(Locale.ROOT);
        }
    }

    private void showTitle(Player player, Action action, Map<String, ?> replacements) {
        Component title = deserialize(actionValue(action, "title", replacements));
        Component subtitle = deserialize(actionValue(action, "subtitle", replacements));
        int fadeIn = integer(action, replacements, 10, "fade-in", "fadeIn");
        int stay = integer(action, replacements, 70, "stay");
        int fadeOut = integer(action, replacements, 20, "fade-out", "fadeOut");
        player.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(ticks(fadeIn), ticks(stay), ticks(fadeOut))
        ));
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0L, ticks) * 50L);
    }

    private static float decimal(Action action, String key, Map<String, ?> replacements) {
        try {
            return Float.parseFloat(actionValue(action, key, replacements));
        } catch (RuntimeException exception) {
            return (float) 1.0;
        }
    }

    private static int integer(Action action, Map<String, ?> replacements, int fallback, String... keys) {
        for (String key : keys) {
            try {
                return Integer.parseInt(actionValue(action, key, replacements));
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    private static List<Action> actions(FileConfiguration configuration, String key) {
        Object raw = configuration.get(key);
        if (raw instanceof List<?> list) {
            List<Action> result = new ArrayList<>(list.size());
            for (Object value : list) {
                result.add(action(value));
            }
            return result.isEmpty() ? List.of(new Action("text", Map.of("text", key))) : List.copyOf(result);
        }
        if (raw instanceof ConfigurationSection section) {
            return List.of(action(section));
        }
        return List.of(new Action("text", Map.of("text", Objects.requireNonNullElse(raw, key))));
    }

    private static Action action(Object raw) {
        if (raw instanceof ConfigurationSection section) {
            return action(section.getValues(false));
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    values.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            String type = String.valueOf(values.getOrDefault("type", "text"))
                    .toLowerCase(Locale.ROOT);
            return new Action(type, Collections.unmodifiableMap(values));
        }
        return new Action("text", Collections.singletonMap("text", raw));
    }

    private static boolean isTextAction(String type) {
        return type.equals("text") || type.equals("message") || type.equals("json") || type.equals("chat");
    }

    private static boolean hasText(Action action) {
        return action.values().containsKey("text") || action.values().containsKey("message");
    }

    private static String actionText(Action action, Map<String, ?> replacements) {
        String key = action.values().containsKey("text") ? "text" : "message";
        return replace(String.valueOf(action.values().getOrDefault(key, "")), replacements);
    }

    private static String actionValue(Action action, String key, Map<String, ?> replacements) {
        return replace(String.valueOf(action.values().getOrDefault(key, "")), replacements);
    }

    private static String replace(String source, Map<String, ?> replacements) {
        String result = source;
        for (Map.Entry<String, ?> entry : replacements.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private Component deserialize(String source) {
        try {
            return miniMessage.deserialize(MessageColorParser.convert(source));
        } catch (RuntimeException exception) {
            return Component.text(source);
        }
    }

    private record Action(String type, Map<String, Object> values) {
    }
}
