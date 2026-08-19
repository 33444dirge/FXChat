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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
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

    public void openGui(Player player) {
        Holder holder = new Holder(player.getUniqueId());
        var inventory = org.bukkit.Bukkit.createInventory(holder, 54, Component.text("屏蔽列表"));
        holder.bind(inventory);
        List<String> names = list(player.getUniqueId());
        for (int index = 0; index < Math.min(names.size(), 45); index++) {
            String name = names.get(index);
            holder.setEntry(index, name);
            inventory.setItem(index, item(org.bukkit.Material.NAME_TAG, "已屏蔽: " + name,
                    "蹲下并右键取消屏蔽"));
        }
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, item(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        inventory.setItem(49, item(org.bukkit.Material.ANVIL, "添加屏蔽玩家", "点击输入玩家 ID"));
        player.openInventory(inventory);
    }

    public void handleClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner().equals(player.getUniqueId())
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 49) {
            player.closeInventory();
            openAddDialog(player);
            return;
        }
        if (slot < 0 || slot >= 45 || !event.isRightClick() || !player.isSneaking()) {
            return;
        }
        String name = holder.entry(slot);
        if (name == null) {
            return;
        }
        remove(player.getUniqueId(), name);
        player.sendMessage(Component.text("已取消屏蔽 " + name + "。"));
        scheduler.runAtEntity(player, () -> openGui(player));
    }

    private void openAddDialog(Player player) {
        DialogInstancesProvider provider = DialogInstancesProvider.instance();
        var input = provider.textBuilder("player", Component.text("玩家 ID"))
                .width(300)
                .maxLength(16)
                .build();
        var saveAction = provider.register((response, audience) -> {
            String name = normalize(response.getText("player"));
            if (!name.matches("[a-z0-9_]{1,16}")) {
                scheduler.runAtEntity(player, () -> player.sendMessage(Component.text("玩家 ID 无效。")));
                return;
            }
            add(player.getUniqueId(), name);
            scheduler.runAtEntity(player, () -> {
                player.sendMessage(Component.text("已屏蔽 " + name + "。"));
                openGui(player);
            });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
        var saveButton = provider.actionButtonBuilder(Component.text("添加")).action(saveAction).build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(provider.dialogBaseBuilder(Component.text("添加屏蔽玩家"))
                        .externalTitle(Component.text("FXChat 屏蔽列表"))
                        .body(List.of(provider.plainMessageDialogBody(
                                Component.text("输入玩家 ID，保存后加入屏蔽列表。"))))
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

    private void add(UUID playerId, String name) {
        ignored.computeIfAbsent(playerId, ignoredId -> ConcurrentHashMap.newKeySet()).add(name);
        saveAsync();
    }

    private void remove(UUID playerId, String name) {
        Set<String> names = ignored.get(playerId);
        if (names == null) return;
        names.remove(normalize(name));
        if (names.isEmpty()) ignored.remove(playerId, names);
        saveAsync();
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

    private static org.bukkit.inventory.ItemStack item(org.bukkit.Material material, String name, String... lore) {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore.length > 0) {
            meta.lore(java.util.Arrays.stream(lore).map(Component::text).toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void close() {
        ignored.clear();
    }

    public static final class Holder implements org.bukkit.inventory.InventoryHolder {
        private final UUID owner;
        private final ConcurrentHashMap<Integer, String> entries = new ConcurrentHashMap<>();
        private org.bukkit.inventory.Inventory inventory;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        private void bind(org.bukkit.inventory.Inventory inventory) {
            this.inventory = inventory;
        }

        private void setEntry(int slot, String name) {
            if (name == null) entries.remove(slot); else entries.put(slot, name);
        }

        private UUID owner() { return owner; }
        private String entry(int slot) { return entries.get(slot); }

        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return inventory;
        }
    }
}
