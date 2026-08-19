package com.dirges.fxchat.bukkit.moderation;

import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.config.MessageService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Persistent per-player chat ignore lists and their configurable inventory interface. */
public final class IgnoreService implements AutoCloseable {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final File file;
    private final File guiFile;
    private final SchedulerFacade scheduler;
    private final PlayerSessionManager sessions;
    private final MessageService messages;
    private final Consumer<String> warning;
    /** Owner UUID -> ignored target UUID and its last known display name. */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, String>> ignored = new ConcurrentHashMap<>();
    private volatile GuiSettings gui = GuiSettings.defaults();

    public IgnoreService(
            File dataFolder,
            SchedulerFacade scheduler,
            PlayerSessionManager sessions,
            MessageService messages,
            Consumer<String> warning
    ) {
        file = new File(new File(dataFolder, "data"), "ignores.yml");
        guiFile = new File(dataFolder, "ignore-gui.yml");
        this.scheduler = scheduler;
        this.sessions = sessions;
        this.messages = messages;
        this.warning = warning;
        load();
        reloadLayout();
    }

    public void reloadLayout() { gui = GuiSettings.load(guiFile, warning); }
    public List<IgnoredPlayer> list(UUID playerId) {
        Map<UUID, String> targets = ignored.get(playerId);
        if (targets == null) return List.of();
        return targets.entrySet().stream()
                .map(entry -> new IgnoredPlayer(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(IgnoredPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
    public void openGui(Player player) { openGui(player, 0); }

    private void openGui(Player player, int requestedPage) {
        GuiSettings settings = gui;
        List<IgnoredPlayer> targets = list(player.getUniqueId());
        int pages = Math.max(1, (targets.size() + settings.entries().size() - 1) / settings.entries().size());
        int page = Math.clamp(requestedPage, 0, pages - 1);
        Holder holder = new Holder(player.getUniqueId(), page, pages);
        Inventory inventory = Bukkit.createInventory(holder, settings.size(), component(settings.title(), page, pages, ""));
        holder.bind(inventory);
        for (int slot : settings.fillers()) inventory.setItem(slot, item(settings.filler(), page, pages, ""));
        for (int slot : settings.add()) inventory.setItem(slot, item(settings.addItem(), page, pages, ""));
        for (int slot : settings.previous()) inventory.setItem(slot, item(page > 0 ? settings.previousItem() : settings.previousDisabled(), page, pages, ""));
        for (int slot : settings.next()) inventory.setItem(slot, item(page + 1 < pages ? settings.nextItem() : settings.nextDisabled(), page, pages, ""));
        int offset = page * settings.entries().size();
        for (int index = 0; index < settings.entries().size() && offset + index < targets.size(); index++) {
            IgnoredPlayer target = targets.get(offset + index);
            int slot = settings.entries().get(index);
            holder.setEntry(slot, target);
            inventory.setItem(slot, item(settings.entry(), page, pages, target.name()));
        }
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner().equals(player.getUniqueId())
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        GuiSettings settings = gui;
        if (settings.add().contains(slot)) { player.closeInventory(); openAddDialog(player); return; }
        if (settings.previous().contains(slot) && holder.page() > 0) { openGui(player, holder.page() - 1); return; }
        if (settings.next().contains(slot) && holder.page() + 1 < holder.pages()) { openGui(player, holder.page() + 1); return; }
        IgnoredPlayer target = holder.entry(slot);
        if (target == null || !event.isShiftClick() || !event.isRightClick()) return;
        remove(player.getUniqueId(), target.id());
        messages.send(player, "ignore.removed", Map.of("player", target.name()));
        scheduler.runAtEntity(player, () -> openGui(player, holder.page()));
    }

    private void openAddDialog(Player player) {
        UUID ownerId = player.getUniqueId();
        DialogInstancesProvider provider = DialogInstancesProvider.instance();
        var input = provider.textBuilder("player", Component.text("玩家 ID")).width(300).maxLength(16).build();
        var saveAction = provider.register((response, audience) -> {
            String name = normalize(response.getText("player"));
            PlayerSessionManager.OnlinePlayer target = sessions.onlineNameIndex().get(name);
            if (target == null) { scheduler.runAtEntity(player, () -> messages.send(player, "ignore.player-not-online")); return; }
            add(ownerId, target.id(), target.name());
            scheduler.runAtEntity(player, () -> { messages.send(player, "ignore.added", Map.of("player", target.name())); openGui(player); });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
        Dialog dialog = Dialog.create(factory -> factory.empty().base(provider.dialogBaseBuilder(Component.text("添加屏蔽玩家"))
                .externalTitle(Component.text("FXChat 屏蔽列表")).body(List.of(provider.plainMessageDialogBody(Component.text("输入玩家 ID，保存后加入屏蔽列表。"))))
                .inputs(List.of(input)).afterAction(DialogBase.DialogAfterAction.CLOSE).build())
                .type(provider.notice(provider.actionButtonBuilder(Component.text("添加")).action(saveAction).build())));
        player.showDialog(dialog);
    }

    public boolean ignores(UUID recipientId, UUID senderId) {
        if (recipientId == null || senderId == null) return false;
        Map<UUID, String> targets = ignored.get(recipientId);
        return targets != null && targets.containsKey(senderId);
    }
    private void add(UUID playerId, UUID targetId, String targetName) {
        ignored.computeIfAbsent(playerId, unused -> new ConcurrentHashMap<>()).put(targetId, targetName);
        saveAsync();
    }
    private void remove(UUID playerId, UUID targetId) {
        ConcurrentHashMap<UUID, String> targets = ignored.get(playerId);
        if (targets == null) return;
        targets.remove(targetId);
        if (targets.isEmpty()) ignored.remove(playerId, targets);
        saveAsync();
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) try {
            UUID id = UUID.fromString(key);
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null) {
                if (!config.getStringList(key).isEmpty()) {
                    warning.accept("Dropped legacy name-based ignore entries for " + key + "; re-add those players while they are online.");
                }
                continue;
            }
            ConcurrentHashMap<UUID, String> targets = new ConcurrentHashMap<>();
            for (String targetKey : section.getKeys(false)) try {
                UUID targetId = UUID.fromString(targetKey);
                String name = section.getString(targetKey, targetId.toString());
                targets.put(targetId, name == null || name.isBlank() ? targetId.toString() : name);
            } catch (IllegalArgumentException exception) { warning.accept("Ignored invalid ignored-player UUID: " + targetKey); }
            if (!targets.isEmpty()) ignored.put(id, targets);
        } catch (IllegalArgumentException exception) { warning.accept("Ignored invalid ignore-list UUID: " + key); }
    }

    private void saveAsync() {
        Map<UUID, Map<UUID, String>> snapshot = new HashMap<>();
        ignored.forEach((id, targets) -> snapshot.put(id, Map.copyOf(targets)));
        scheduler.runAsync(() -> {
            YamlConfiguration config = new YamlConfiguration();
            snapshot.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString())).forEach(entry ->
                    entry.getValue().entrySet().stream().sorted(Comparator.comparing(target -> target.getKey().toString())).forEach(target ->
                            config.set(entry.getKey() + "." + target.getKey(), target.getValue())));
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Could not create " + parent);
                config.save(file);
            } catch (IOException exception) { warning.accept("Could not save player ignore lists: " + exception.getMessage()); }
        });
    }

    private static ItemStack item(ItemSettings settings, int page, int pages, String player) {
        ItemStack item = new ItemStack(settings.material()); ItemMeta meta = item.getItemMeta();
        meta.displayName(component(settings.name(), page, pages, player).decoration(TextDecoration.ITALIC, false));
        meta.lore(settings.lore().stream()
                .map(line -> component(line, page, pages, player).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    private static Component component(String text, int page, int pages, String player) {
        return MINI_MESSAGE.deserialize(text.replace("{page}", String.valueOf(page + 1)).replace("{pages}", String.valueOf(pages)).replace("{player}", player));
    }
    private static String normalize(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
    @Override public void close() { ignored.clear(); }

    public static final class Holder implements InventoryHolder {
        private final UUID owner; private final int page; private final int pages; private final Map<Integer, IgnoredPlayer> entries = new HashMap<>(); private Inventory inventory;
        private Holder(UUID owner, int page, int pages) { this.owner = owner; this.page = page; this.pages = pages; }
        private void bind(Inventory inventory) { this.inventory = inventory; } private void setEntry(int slot, IgnoredPlayer target) { entries.put(slot, target); }
        private UUID owner() { return owner; } private int page() { return page; } private int pages() { return pages; } private IgnoredPlayer entry(int slot) { return entries.get(slot); }
        @Override public Inventory getInventory() { return inventory; }
    }

    public record IgnoredPlayer(UUID id, String name) { }

    private record ItemSettings(Material material, String name, List<String> lore) { }
    private record GuiSettings(String title, int size, List<Integer> fillers, List<Integer> entries, List<Integer> add, List<Integer> previous, List<Integer> next,
                               ItemSettings filler, ItemSettings entry, ItemSettings addItem, ItemSettings previousItem, ItemSettings previousDisabled, ItemSettings nextItem, ItemSettings nextDisabled) {
        private static GuiSettings load(File file, Consumer<String> warning) {
            if (!file.isFile()) return defaults();
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file); List<String> layout = config.getStringList("layout");
                if (layout.isEmpty() || layout.size() > 6 || layout.stream().anyMatch(line -> line.length() != 9)) throw new IllegalArgumentException("layout must contain 1-6 rows of 9 characters");
                List<Integer> fillers = new ArrayList<>(), entries = new ArrayList<>(), add = new ArrayList<>(), previous = new ArrayList<>(), next = new ArrayList<>();
                for (int row = 0; row < layout.size(); row++) for (int column = 0; column < 9; column++) {
                    int slot = row * 9 + column;
                    switch (Character.toLowerCase(layout.get(row).charAt(column))) {
                        case 'x' -> fillers.add(slot); case 'a' -> entries.add(slot); case 'b' -> add.add(slot); case 'p' -> previous.add(slot); case 'n' -> next.add(slot); case ' ' -> { }
                        default -> throw new IllegalArgumentException("unknown layout character");
                    }
                }
                if (entries.isEmpty()) throw new IllegalArgumentException("layout needs at least one a slot");
                return new GuiSettings(config.getString("title", "屏蔽列表"), layout.size() * 9, List.copyOf(fillers), List.copyOf(entries), List.copyOf(add), List.copyOf(previous), List.copyOf(next),
                        item(config, "filler"), item(config, "entry"), item(config, "add"), item(config, "previous"), item(config, "previous.disabled"), item(config, "next"), item(config, "next.disabled"));
            } catch (RuntimeException exception) { warning.accept("Could not load ignore-gui.yml: " + exception.getMessage()); return defaults(); }
        }
        private static ItemSettings item(YamlConfiguration config, String path) {
            ConfigurationSection section = config.getConfigurationSection(path); if (section == null) return new ItemSettings(Material.BARRIER, "<red>配置错误", List.of());
            Material material = Material.matchMaterial(section.getString("material", "BARRIER")); return new ItemSettings(material == null ? Material.BARRIER : material, section.getString("name", ""), List.copyOf(section.getStringList("lore")));
        }
        private static GuiSettings defaults() {
            ItemSettings filler = new ItemSettings(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()), entry = new ItemSettings(Material.NAME_TAG, "<red>已屏蔽: <white>{player}", List.of("<gray>Shift + 右键取消屏蔽")), add = new ItemSettings(Material.ANVIL, "<green>添加屏蔽玩家", List.of()), arrow = new ItemSettings(Material.ARROW, "<yellow>翻页", List.of()), disabled = new ItemSettings(Material.BARRIER, "<gray>没有更多页面", List.of());
            return new GuiSettings("屏蔽列表", 36, List.of(0,1,2,3,4,5,6,7,8,9,17,18,26,28,29,30,32,33,34), List.of(10,11,12,13,14,15,16,19,20,21,22,23,24,25), List.of(31), List.of(27), List.of(35), filler, entry, add, arrow, disabled, arrow, disabled);
        }
    }
}
