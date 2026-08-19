package com.dirges.fxchat.bukkit.moderation;

import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Persistent per-player chat ignore lists. */
public final class IgnoreService implements AutoCloseable {
    private final File file;
    private final SchedulerFacade scheduler;
    private final Consumer<String> warning;
    private final ConcurrentHashMap<UUID, Set<String>> ignored = new ConcurrentHashMap<>();

    public IgnoreService(File dataFolder, SchedulerFacade scheduler, Consumer<String> warning) {
        this.file = new File(new File(dataFolder, "data"), "ignores.yml");
        this.scheduler = scheduler;
        this.warning = warning;
        load();
    }

    public List<String> list(UUID playerId) {
        return ignored.getOrDefault(playerId, Set.of()).stream().sorted().toList();
    }

    public void openDialog(Player player) {
        DialogInstancesProvider provider = DialogInstancesProvider.instance();
        String current = String.join(", ", list(player.getUniqueId()));
        var input = provider.textBuilder("ignored", Component.text("玩家名称（逗号分隔）"))
                .width(300)
                .initial(current)
                .maxLength(1024)
                .build();
        var saveAction = provider.register((response, audience) -> {
            String raw = response.getText("ignored");
            List<String> names = raw == null ? List.of() : Arrays.stream(raw.split("[,\\s]+"))
                    .filter(value -> !value.isBlank())
                    .toList();
            replace(player.getUniqueId(), names);
            player.sendMessage(Component.text("屏蔽列表已保存，共 " + list(player.getUniqueId()).size() + " 人。"));
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
        var saveButton = provider.actionButtonBuilder(Component.text("保存")).action(saveAction).build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(provider.dialogBaseBuilder(Component.text("屏蔽玩家"))
                        .externalTitle(Component.text("FXChat 屏蔽列表"))
                        .body(List.of(provider.plainMessageDialogBody(
                                Component.text(current.isBlank()
                                        ? "当前没有屏蔽玩家。输入玩家名后保存即可。"
                                        : "当前列表：" + current))))
                        .inputs(List.of(input))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(provider.notice(saveButton)));
        player.showDialog(dialog);
    }

    public boolean ignores(UUID recipientId, String senderName) {
        if (senderName == null || senderName.isBlank()) {
            return false;
        }
        return ignored.getOrDefault(recipientId, Set.of()).contains(normalize(senderName));
    }

    public void replace(UUID playerId, List<String> names) {
        Set<String> values = ConcurrentHashMap.newKeySet();
        for (String name : names) {
            String normalized = normalize(name);
            if (normalized.matches("[a-z0-9_]{1,16}")) {
                values.add(normalized);
            }
        }
        if (values.isEmpty()) {
            ignored.remove(playerId);
        } else {
            ignored.put(playerId, values);
        }
        saveAsync();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                Set<String> names = ConcurrentHashMap.newKeySet();
                for (String name : config.getStringList(key)) {
                    String normalized = normalize(name);
                    if (normalized.matches("[a-z0-9_]{1,16}")) {
                        names.add(normalized);
                    }
                }
                if (!names.isEmpty()) {
                    ignored.put(playerId, names);
                }
            } catch (IllegalArgumentException exception) {
                warning.accept("Ignored invalid ignore-list UUID: " + key);
            }
        }
    }

    private void saveAsync() {
        ConcurrentHashMap<UUID, List<String>> snapshot = new ConcurrentHashMap<>();
        ignored.forEach((id, names) -> snapshot.put(id, names.stream().sorted().toList()));
        scheduler.runAsync(() -> {
            YamlConfiguration config = new YamlConfiguration();
            snapshot.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> config.set(entry.getKey().toString(), entry.getValue()));
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Could not create " + parent);
                }
                config.save(file);
            } catch (IOException exception) {
                warning.accept("Could not save player ignore lists: " + exception.getMessage());
            }
        });
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        ignored.clear();
    }
}
