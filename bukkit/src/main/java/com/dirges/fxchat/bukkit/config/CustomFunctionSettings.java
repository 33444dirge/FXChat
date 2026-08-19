package com.dirges.fxchat.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public record CustomFunctionSettings(List<Rule> rules) {
    public CustomFunctionSettings {
        rules = List.copyOf(rules);
    }

    public static CustomFunctionSettings load(File file, Consumer<String> warning) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection custom = config.getConfigurationSection("Custom");
        if (custom == null) {
            return new CustomFunctionSettings(List.of());
        }

        List<Rule> rules = new ArrayList<>();
        for (String id : custom.getKeys(false)) {
            ConfigurationSection section = custom.getConfigurationSection(id);
            if (section == null) {
                warning.accept("Ignored custom function without a section: " + id);
                continue;
            }
            String rawPattern = section.getString("pattern", "").trim();
            if (rawPattern.isEmpty()) {
                warning.accept("Ignored custom function without a pattern: " + id);
                continue;
            }
            try {
                Pattern pattern = Pattern.compile(rawPattern);
                String rawFilter = section.getString("text-filter", "").trim();
                Pattern textFilter = rawFilter.isEmpty() ? null : Pattern.compile(rawFilter);
                ConfigurationSection display = section.getConfigurationSection("display");
                if (display == null) {
                    warning.accept("Ignored custom function without display settings: " + id);
                    continue;
                }
                rules.add(new Rule(
                        id,
                        section.getBoolean("enabled", true),
                        section.getInt("priority", 100),
                        pattern,
                        textFilter,
                        new Display(display.getString("text", "{0}"))
                ));
            } catch (RuntimeException exception) {
                warning.accept("Ignored invalid custom function " + id + ": " + exception.getMessage());
            }
        }
        rules.sort(Comparator.comparingInt(Rule::priority));
        return new CustomFunctionSettings(rules);
    }

    public record Rule(
            String id,
            boolean enabled,
            int priority,
            Pattern pattern,
            Pattern textFilter,
            Display display
    ) {
    }

    public record Display(String text) {
    }
}
