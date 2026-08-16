package com.dirges.fxchat.bukkit.function;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryType;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, immutable snapshots used by local and cross-server view commands. */
public final class ShowcaseStore implements AutoCloseable {
    private static final int MAX_ENTRIES = 512;
    private static final long TTL_MILLIS = 5 * 60_000L;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public String put(Showcase showcase) {
        String token = newToken();
        put(token, showcase);
        return token;
    }

    public void put(String token, Showcase showcase) {
        if (token == null || token.isBlank() || showcase == null) {
            return;
        }
        cleanup();
        entries.put(token, new Entry(showcase, System.currentTimeMillis() + TTL_MILLIS));
    }

    public Showcase get(String token, Kind expectedKind) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Entry entry = entries.get(token);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()
                || (expectedKind != null && entry.showcase().kind() != expectedKind)) {
            if (entry != null && entry.expiresAt() < System.currentTimeMillis()) {
                entries.remove(token, entry);
            }
            return null;
        }
        return entry.showcase();
    }

    public static String serialize(Showcase showcase) {
        ItemStack[] items = showcase.items();
        StringBuilder result = new StringBuilder(items.length * 32 + 64)
                .append(showcase.kind().name()).append('|')
                .append(encode(showcase.title())).append('|')
                .append("@fxmeta:").append(showcase.heldSlot()).append(':')
                .append(encode(showcase.ownerName())).append(':')
                .append(items.length).append(':')
                .append(showcase.containerType().name()).append(';');
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            String displayName = "";
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                displayName = meta.getDisplayName();
            }
            result.append(slot).append(':')
                    .append(item.getType().name()).append(':')
                    .append(Math.clamp(item.getAmount(), 1, item.getMaxStackSize())).append(':')
                    .append(encode(displayName)).append(';');
        }
        return result.toString();
    }

    public static Showcase deserialize(String value) {
        if (value == null || value.length() > 16_384) {
            return null;
        }
        String[] sections = value.split("\\|", -1);
        if (sections.length != 3 && sections.length != 5) {
            return null;
        }
        try {
            Kind kind = Kind.valueOf(sections[0]);
            String title = decode(sections[1]);
            int heldSlot = sections.length == 5 ? Integer.parseInt(sections[2]) : -1;
            String ownerName = sections.length == 5 ? decode(sections[3]) : "";
            String itemSection = sections.length == 5 ? sections[4] : sections[2];
            int containerSize = 54;
            InventoryType containerType = InventoryType.CHEST;
            for (String entry : itemSection.split(";")) {
                if (!entry.startsWith("@fxmeta:")) {
                    continue;
                }
                String[] metadata = entry.split(":", -1);
                if (metadata.length >= 3) {
                    heldSlot = Integer.parseInt(metadata[1]);
                    ownerName = decode(metadata[2]);
                }
                if (metadata.length >= 4) {
                    containerSize = Integer.parseInt(metadata[3]);
                }
                if (metadata.length >= 5) {
                    try {
                        containerType = InventoryType.valueOf(metadata[4]);
                    } catch (IllegalArgumentException ignored) {
                        // Newer server container types fall back to a chest on older servers.
                    }
                }
            }
            int size = switch (kind) {
                case ENDER_CHEST, ITEM -> 27;
                case INVENTORY -> 54;
                case CONTAINER -> Math.clamp(containerSize, 1, 54);
            };
            ItemStack[] items = new ItemStack[size];
            if (!itemSection.isBlank()) {
                for (String entry : itemSection.split(";")) {
                    if (entry.startsWith("@fxmeta:")) {
                        continue;
                    }
                    String[] fields = entry.split(":", 4);
                    if (fields.length != 4) {
                        continue;
                    }
                    int slot = Integer.parseInt(fields[0]);
                    Material material = Material.matchMaterial(fields[1]);
                    int amount = Integer.parseInt(fields[2]);
                    if (slot < 0 || slot >= items.length || material == null || material.isAir()) {
                        continue;
                    }
                    ItemStack item = new ItemStack(material, Math.clamp(amount, 1, material.getMaxStackSize()));
                    String displayName = decode(fields[3]);
                    if (!displayName.isBlank()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setDisplayName(displayName);
                            item.setItemMeta(meta);
                        }
                    }
                    items[slot] = item;
                }
            }
            return new Showcase(kind, title, items, heldSlot, ownerName, containerType);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void clear() {
        entries.clear();
    }

    @Override
    public void close() {
        clear();
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        if (entries.size() >= MAX_ENTRIES) {
            int remove = entries.size() - MAX_ENTRIES + 1;
            for (String token : entries.keySet()) {
                entries.remove(token);
                if (--remove <= 0) {
                    break;
                }
            }
        }
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        int padding = (4 - value.length() % 4) % 4;
        return new String(Base64.getUrlDecoder().decode(value + "=".repeat(padding)), StandardCharsets.UTF_8);
    }

    public enum Kind {
        ITEM,
        INVENTORY,
        ENDER_CHEST,
        CONTAINER
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        public void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NonNull Inventory getInventory() {
            return inventory;
        }
    }

    public record Showcase(
            Kind kind,
            String title,
            ItemStack[] items,
            int heldSlot,
            String ownerName,
            InventoryType containerType
    ) {
        public Showcase(Kind kind, String title, ItemStack[] items) {
            this(kind, title, items, -1, "", InventoryType.CHEST);
        }

        public Showcase(Kind kind, String title, ItemStack[] items, int heldSlot, String ownerName) {
            this(kind, title, items, heldSlot, ownerName, InventoryType.CHEST);
        }

        public Showcase {
            title = title == null ? "FXChat" : title;
            heldSlot = heldSlot >= 0 && heldSlot < 9 ? heldSlot : -1;
            ownerName = ownerName == null ? "" : ownerName;
            containerType = containerType == null || !containerType.isCreatable()
                    ? InventoryType.CHEST : containerType;
            items = copy(items);
        }

        public ItemStack[] copyItems() {
            return copy(items);
        }

        @Override
        public ItemStack[] items() {
            return copy(items);
        }

        private static ItemStack[] copy(ItemStack[] source) {
            ItemStack[] result = source == null ? new ItemStack[0] : source.clone();
            for (int i = 0; i < result.length; i++) {
                if (result[i] != null) {
                    result[i] = result[i].clone();
                }
            }
            return result;
        }
    }

    private record Entry(Showcase showcase, long expiresAt) {
    }
}
