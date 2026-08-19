package com.dirges.fxchat.bukkit.moderation;

import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Persistent per-player chat ignore lists and their configurable inventory interface. */
public final class IgnoreService implements AutoCloseable {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final File file;
    private final File guiFile;
    private final SchedulerFacade scheduler;
    private final Consumer<String> warning;
    private final ConcurrentHashMap<UUID, Set<String>> ignored = new ConcurrentHashMap<>();
    private volatile GuiSettings gui = GuiSettings.defaults();

    public IgnoreService(File dataFolder, SchedulerFacade scheduler, Consumer<String> warning) {
        file = new File(new File(dataFolder, "data"), "ignores.yml");
        guiFile = new File(dataFolder, "ignore-gui.yml");
        this.scheduler = scheduler;
        this.warning = warning;
        load();
        reloadLayout();
    }

    public void reloadLayout() { gui = GuiSettings.load(guiFile, warning); }
    public List<String> list(UUID playerId) { return ignored.getOrDefault(playerId, Set.of()).stream().sorted().toList(); }
    public void openGui(Player player) { openGui(player, 0); }

    private void openGui(Player player, int requestedPage) {
        GuiSettings settings = gui;
        List<String> names = list(player.getUniqueId());
        int pages = Math.max(1, (names.size() + settings.entries().size() - 1) / settings.entries().size());
        int page = Math.clamp(requestedPage, 0, pages - 1);
        Holder holder = new Holder(player.getUniqueId(), page, pages);
        Inventory inventory = Bukkit.createInventory(holder, settings.size(), component(settings.title(), page, pages, ""));
        holder.bind(inventory);
        for (int slot : settings.fillers()) inventory.setItem(slot, item(settings.filler(), page, pages, ""));
        for (int slot : settings.add()) inventory.setItem(slot, item(settings.addItem(), page, pages, ""));
        for (int slot : settings.previous()) inventory.setItem(slot, item(page > 0 ? settings.previousItem() : settings.previousDisabled(), page, pages, ""));
        for (int slot : settings.next()) inventory.setItem(slot, item(page + 1 < pages ? settings.nextItem() : settings.nextDisabled(), page, pages, ""));
        int offset = page * settings.entries().size();
        for (int index = 0; index < settings.entries().size() && offset + index < names.size(); index++) {
            String name = names.get(offset + index);
            int slot = settings.entries().get(index);
            holder.setEntry(slot, name);
            inventory.setItem(slot, item(settings.entry(), page, pages, name));
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
        String name = holder.entry(slot);
        if (name == null || !event.isShiftClick() || !event.isRightClick()) return;
        remove(player.getUniqueId(), name);
        player.sendMessage(Component.text("已取消屏蔽 " + name + "。"));
        scheduler.runAtEntity(player, () -> openGui(player, holder.page()));
    }

    private void openAddDialog(Player player) {
        DialogInstancesProvider provider = DialogInstancesProvider.instance();
        var input = provider.textBuilder("player", Component.text("玩家 ID")).width(300).maxLength(16).build();
        var saveAction = provider.register((response, audience) -> {
            String name = normalize(response.getText("player"));
            if (!name.matches("[a-z0-9_]{1,16}")) { scheduler.runAtEntity(player, () -> player.sendMessage(Component.text("玩家 ID 无效。"))); return; }
            add(player.getUniqueId(), name);
            scheduler.runAtEntity(player, () -> { player.sendMessage(Component.text("已屏蔽 " + name + "。")); openGui(player); });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
        Dialog dialog = Dialog.create(factory -> factory.empty().base(provider.dialogBaseBuilder(Component.text("添加屏蔽玩家"))
                .externalTitle(Component.text("FXChat 屏蔽列表")).body(List.of(provider.plainMessageDialogBody(Component.text("输入玩家 ID，保存后加入屏蔽列表。"))))
                .inputs(List.of(input)).afterAction(DialogBase.DialogAfterAction.CLOSE).build())
                .type(provider.notice(provider.actionButtonBuilder(Component.text("添加")).action(saveAction).build())));
        player.showDialog(dialog);
    }

    public boolean ignores(UUID recipientId, String senderName) { return senderName != null && !senderName.isBlank() && ignored.getOrDefault(recipientId, Set.of()).contains(normalize(senderName)); }
    private void add(UUID playerId, String name) { ignored.computeIfAbsent(playerId, unused -> ConcurrentHashMap.newKeySet()).add(name); saveAsync(); }
    private void remove(UUID playerId, String name) { Set<String> names = ignored.get(playerId); if (names == null) return; names.remove(normalize(name)); if (names.isEmpty()) ignored.remove(playerId, names); saveAsync(); }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) try {
            UUID id = UUID.fromString(key); Set<String> names = ConcurrentHashMap.newKeySet();
            for (String name : config.getStringList(key)) { String normalized = normalize(name); if (normalized.matches("[a-z0-9_]{1,16}")) names.add(normalized); }
            if (!names.isEmpty()) ignored.put(id, names);
        } catch (IllegalArgumentException exception) { warning.accept("Ignored invalid ignore-list UUID: " + key); }
    }

    private void saveAsync() {
        Map<UUID, List<String>> snapshot = new HashMap<>();
        ignored.forEach((id, names) -> snapshot.put(id, names.stream().sorted().toList()));
        scheduler.runAsync(() -> {
            YamlConfiguration config = new YamlConfiguration();
            snapshot.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString())).forEach(entry -> config.set(entry.getKey().toString(), entry.getValue()));
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Could not create " + parent);
                config.save(file);
            } catch (IOException exception) { warning.accept("Could not save player ignore lists: " + exception.getMessage()); }
        });
    }

    private static ItemStack item(ItemSettings settings, int page, int pages, String player) {
        ItemStack item = new ItemStack(settings.material()); ItemMeta meta = item.getItemMeta();
        meta.displayName(component(settings.name(), page, pages, player)); meta.lore(settings.lore().stream().map(line -> component(line, page, pages, player)).toList()); item.setItemMeta(meta); return item;
    }
    private static Component component(String text, int page, int pages, String player) {
        return MINI_MESSAGE.deserialize(text.replace("{page}", String.valueOf(page + 1)).replace("{pages}", String.valueOf(pages)).replace("{player}", player));
    }
    private static String normalize(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
    @Override public void close() { ignored.clear(); }

    public static final class Holder implements InventoryHolder {
        private final UUID owner; private final int page; private final int pages; private final Map<Integer, String> entries = new HashMap<>(); private Inventory inventory;
        private Holder(UUID owner, int page, int pages) { this.owner = owner; this.page = page; this.pages = pages; }
        private void bind(Inventory inventory) { this.inventory = inventory; } private void setEntry(int slot, String name) { entries.put(slot, name); }
        private UUID owner() { return owner; } private int page() { return page; } private int pages() { return pages; } private String entry(int slot) { return entries.get(slot); }
        @Override public Inventory getInventory() { return inventory; }
    }

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
