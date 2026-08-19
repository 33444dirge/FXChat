package com.dirges.fxchat.bukkit.function;

import com.dirges.fxchat.bukkit.config.FunctionSettings;
import com.dirges.fxchat.bukkit.config.CustomFunctionSettings;
import com.dirges.fxchat.bukkit.config.MessageService;
import com.dirges.fxchat.bukkit.hook.CraftEngineHook;
import com.dirges.fxchat.bukkit.hook.PapiHook;
import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.dirges.fxchat.bukkit.text.MessageColorParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatFunctionService implements AutoCloseable {
    private final SchedulerFacade scheduler;
    private final PlayerSessionManager sessions;
    private final MessageService messages;
    private final ShowcaseStore showcases;
    private final CraftEngineHook craftEngine;
    private final PapiHook papi;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Object mentionPatternLock = new Object();
    private volatile FunctionState state;
    private volatile CustomFunctionSettings customFunctions;
    private volatile MentionPatternCache mentionPatternCache =
            new MentionPatternCache(Long.MIN_VALUE, null, null);

    public ChatFunctionService(
            SchedulerFacade scheduler,
            PlayerSessionManager sessions,
            MessageService messages,
            ShowcaseStore showcases,
            CraftEngineHook craftEngine,
            PapiHook papi,
            FunctionSettings settings,
            CustomFunctionSettings customFunctions
    ) {
        this.scheduler = scheduler;
        this.sessions = sessions;
        this.messages = messages;
        this.showcases = showcases;
        this.craftEngine = craftEngine;
        this.papi = papi;
        this.state = new FunctionState(settings, FunctionPatterns.create(settings));
        this.customFunctions = customFunctions;
    }

    public void updateSettings(FunctionSettings settings) {
        this.state = new FunctionState(settings, FunctionPatterns.create(settings));
        mentionPatternCache = new MentionPatternCache(Long.MIN_VALUE, null, null);
    }

    public void updateCustomFunctions(CustomFunctionSettings customFunctions) {
        this.customFunctions = customFunctions;
    }

    public PreparedMessage prepare(Player sender, String message) {
        FunctionState currentState = state;
        FunctionSettings current = currentState.settings();
        FunctionPatterns currentPatterns = currentState.patterns();
        String result = message;
        List<Token> tokens = new ArrayList<>();
        Set<UUID> mentionedPlayers = new LinkedHashSet<>();
        Map<String, String> wireShowcases = new LinkedHashMap<>();
        String beforeMentionAll = result;
        result = replaceMentionAll(sender, current.mentionAll(), currentPatterns.mentionAll(), result, tokens);
        boolean mentionAll = !beforeMentionAll.equals(result);

        result = replaceMentions(sender, current.mention(), result, tokens, mentionedPlayers);

        result = replaceCustomFunctions(sender, customFunctions, result, tokens);
        result = replaceSimpleShowcase(sender, current.inventoryShow(), currentPatterns.inventory(), result, tokens, wireShowcases,
                ShowcaseStore.Kind.INVENTORY);
        result = replaceSimpleShowcase(sender, current.enderChestShow(), currentPatterns.enderChest(), result, tokens, wireShowcases,
                ShowcaseStore.Kind.ENDER_CHEST);
        result = replaceContainer(sender, current.containerShow(), currentPatterns.container(), result, tokens, wireShowcases);
        result = replaceItem(sender, current.itemShow(), currentPatterns.item(), result, tokens, wireShowcases);
        return new PreparedMessage(result, tokens, mentionedPlayers, mentionAll, wireShowcases);
    }

    public void notifyMention(
            Player recipient,
            UUID senderId,
            String senderName,
            Set<UUID> mentionedPlayers,
            boolean mentionAll
    ) {
        FunctionSettings current = state.settings();
        if (recipient.getUniqueId().equals(senderId)) {
            return;
        }
        if ((mentionAll && current.mentionAll().notifyEnabled())
                || (mentionedPlayers.contains(recipient.getUniqueId())
                && current.mention().notifyEnabled())) {
            messages.sendActionBar(recipient, "function.mention-notify", Map.of("player", senderName));
        }
    }

    public void importShowcases(Map<String, String> remoteShowcases) {
        if (remoteShowcases == null || remoteShowcases.isEmpty()) {
            return;
        }
        remoteShowcases.entrySet().stream().limit(32).forEach(entry -> {
            ShowcaseStore.Showcase showcase = ShowcaseStore.deserialize(entry.getValue());
            if (showcase != null) {
                showcases.put(entry.getKey(), showcase);
            }
        });
    }

    public void openShowcase(Player player, ShowcaseStore.Kind kind, String token) {
        ShowcaseStore.Showcase snapshot = showcases.get(token, kind);
        if (snapshot == null) {
            messages.send(player, switch (kind) {
                case ITEM -> "function.item-unavailable";
                case INVENTORY -> "function.inventory-unavailable";
                case ENDER_CHEST -> "function.enderchest-unavailable";
                case CONTAINER -> "function.container-unavailable";
            });
            return;
        }
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            ShowcaseStore.Holder holder = new ShowcaseStore.Holder();
            Inventory inventory = createShowcaseInventory(holder, snapshot);
            holder.bind(inventory);
            populateShowcase(inventory, snapshot);
            player.openInventory(inventory);
        });
    }

    private Inventory createShowcaseInventory(ShowcaseStore.Holder holder, ShowcaseStore.Showcase snapshot) {
        Component title = deserializeTemplate(snapshot.title());
        if (snapshot.kind() == ShowcaseStore.Kind.CONTAINER
                && snapshot.containerType() != InventoryType.CHEST) {
            return Bukkit.createInventory(holder, snapshot.containerType(), title);
        }
        int size = switch (snapshot.kind()) {
            case ITEM, ENDER_CHEST -> 27;
            case INVENTORY -> 54;
            case CONTAINER -> containerGuiSize(snapshot.items().length);
        };
        return Bukkit.createInventory(holder, size, title);
    }

    private void populateShowcase(Inventory inventory, ShowcaseStore.Showcase snapshot) {
        switch (snapshot.kind()) {
            case ITEM -> populateItemShowcase(inventory, snapshot.copyItems());
            case INVENTORY -> populateInventoryShowcase(inventory, snapshot);
            case ENDER_CHEST -> populateEnderChestShowcase(inventory, snapshot.copyItems());
            case CONTAINER -> populateContainerShowcase(inventory, snapshot.copyItems());
        }
    }

    private static void populateItemShowcase(Inventory inventory, ItemStack[] items) {
        for (int slot = 0; slot < 27; slot++) {
            inventory.setItem(slot, pane(Material.BLACK_STAINED_GLASS_PANE));
        }
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                inventory.setItem(13, item);
                break;
            }
        }
    }

    private static void populateEnderChestShowcase(Inventory inventory, ItemStack[] items) {
        for (int slot = 0; slot < 27; slot++) {
            inventory.setItem(slot, displayItem(itemAt(items, slot)));
        }
    }

    private static void populateInventoryShowcase(Inventory inventory, ShowcaseStore.Showcase snapshot) {
        ItemStack[] items = snapshot.copyItems();
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, pane(Material.WHITE_STAINED_GLASS_PANE));
        }
        for (int slot = 9; slot < 18; slot++) {
            inventory.setItem(slot, pane(Material.WHITE_STAINED_GLASS_PANE));
        }
        inventory.setItem(1, displayItem(itemAt(items, 49)));
        inventory.setItem(2, playerHead(snapshot.ownerName()));
        if (snapshot.heldSlot() >= 0) {
            inventory.setItem(3, displayItem(itemAt(items, snapshot.heldSlot())));
        } else {
            inventory.setItem(3, pane(Material.GRAY_STAINED_GLASS_PANE));
        }
        inventory.setItem(5, displayItem(itemAt(items, 48)));
        inventory.setItem(6, displayItem(itemAt(items, 47)));
        inventory.setItem(7, displayItem(itemAt(items, 46)));
        inventory.setItem(8, displayItem(itemAt(items, 45)));

        for (int slot = 9; slot <= 35; slot++) {
            inventory.setItem(18 + slot - 9, displayItem(itemAt(items, slot)));
        }
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(45 + slot, displayItem(itemAt(items, slot)));
        }
    }

    private static void populateContainerShowcase(Inventory inventory, ItemStack[] items) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, displayItem(itemAt(items, slot)));
        }
    }

    private static int containerGuiSize(int inventorySize) {
        int rows = Math.max(1, (Math.max(1, inventorySize) + 8) / 9);
        return Math.clamp(rows, 1, 6) * 9;
    }

    private static ItemStack itemAt(ItemStack[] items, int slot) {
        return slot >= 0 && slot < items.length ? items[slot] : null;
    }

    private static ItemStack displayItem(ItemStack item) {
        return item == null || item.getType().isAir()
                ? pane(Material.GRAY_STAINED_GLASS_PANE)
                : item;
    }

    private static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack playerHead(String ownerName) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();
        if (rawMeta instanceof SkullMeta meta) {
            if (!ownerName.isBlank()) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerName));
            }
            meta.displayName(Component.text(ownerName.isBlank() ? " " : ownerName));
            item.setItemMeta(meta);
        }
        return item;
    }

    public void removePlayer(UUID playerId) {
        cooldowns.remove(playerId);
    }

    @Override
    public void close() {
        cooldowns.clear();
        showcases.clear();
    }

    private String replaceMentionAll(
            Player sender,
            FunctionSettings.MentionAll config,
            Pattern pattern,
            String source,
            List<Token> tokens
    ) {
        if (!config.enabled() || hasPermission(sender, config.permission()) || config.keys().isEmpty()) {
            return source;
        }
        if (pattern == null) {
            return source;
        }
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        if (acquire(sender, "mention-all", config.cooldownMillis(), "mention-all-cooldown")) {
            return source;
        }
        StringBuilder output = new StringBuilder(source.length() + 16);
        int end = 0;
        do {
            output.append(source, end, matcher.start());
            String key = addToken(tokens, mentionAllComponent());
            output.append('<').append(key).append('>');
            end = matcher.end();
        } while (matcher.find());
        output.append(source, end, source.length());
        return output.toString();
    }

    private String replaceMentions(
            Player sender,
            FunctionSettings.Mention config,
            String source,
            List<Token> tokens,
            Set<UUID> mentionedPlayers
    ) {
        if (!config.enabled() || hasPermission(sender, config.permission())) {
            return source;
        }
        long directoryVersion = sessions.onlineDirectoryVersion();
        Map<String, PlayerSessionManager.OnlinePlayer> names = sessions.onlineNameIndex();
        if (names.isEmpty()) {
            return source;
        }
        Pattern pattern = mentionPattern(config, names, directoryVersion);
        if (pattern == null) {
            return source;
        }
        Matcher matcher = pattern.matcher(source);
        List<MentionMatch> matches = new ArrayList<>();
        while (matcher.find()) {
            String matchedName;
            try {
                matchedName = matcher.group("fxname");
            } catch (IllegalArgumentException exception) {
                return source;
            }
            PlayerSessionManager.OnlinePlayer target = names.get(matchedName.toLowerCase(Locale.ROOT));
            if (target != null && (config.selfMention() || !target.id().equals(sender.getUniqueId()))) {
                matches.add(new MentionMatch(matcher.start(), matcher.end(), target));
            }
        }
        if (matches.isEmpty() || acquire(sender, "mention", config.cooldownMillis(), "mention-cooldown")) {
            return source;
        }
        StringBuilder output = new StringBuilder(source.length() + matches.size() * 8);
        int end = 0;
        for (MentionMatch match : matches) {
            output.append(source, end, match.start());
            String key = addToken(tokens, mentionComponent(match.target().name()));
            output.append('<').append(key).append('>');
            mentionedPlayers.add(match.target().id());
            end = match.end();
        }
        output.append(source, end, source.length());
        return output.toString();
    }

    private String replaceItem(
            Player sender,
            FunctionSettings.Showcase config,
            Pattern pattern,
            String source,
            List<Token> tokens,
            Map<String, String> wireShowcases
    ) {
        if (!config.enabled() || hasPermission(sender, config.permission())) {
            return source;
        }
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()
                || acquire(sender, "item", config.cooldownMillis(), "item-cooldown")) {
            return source;
        }
        StringBuilder output = new StringBuilder(source.length() + 16);
        int end = 0;
        do {
            output.append(source, end, matcher.start());
            String hotbarSlot = matchedHotbarSlot(matcher);
            ItemStack item = hotbarSlot == null
                    ? sender.getInventory().getItemInMainHand()
                    : itemAtHotbar(sender.getInventory(), Integer.parseInt(hotbarSlot));
            Component component;
            if (item == null || item.getType().isAir()) {
                component = messages.component("function.item-air");
            } else {
                component = itemComponent(sender, item, config, wireShowcases);
            }
            String key = addToken(tokens, component);
            output.append('<').append(key).append('>');
            end = matcher.end();
        } while (matcher.find());
        output.append(source, end, source.length());
        return output.toString();
    }

    private String replaceCustomFunctions(
            Player sender,
            CustomFunctionSettings config,
            String source,
            List<Token> tokens
    ) {
        String result = source;
        for (CustomFunctionSettings.Rule rule : config.rules()) {
            if (!rule.enabled()) {
                continue;
            }
            result = replaceCustomFunction(sender, rule, result, tokens);
        }
        return result;
    }

    private String replaceCustomFunction(
            Player sender,
            CustomFunctionSettings.Rule rule,
            String source,
            List<Token> tokens
    ) {
        Matcher matcher = rule.pattern().matcher(source);
        if (!matcher.find()) {
            return source;
        }
        StringBuilder output = new StringBuilder(source.length() + 16);
        int end = 0;
        do {
            output.append(source, end, matcher.start());
            String matched = matcher.group();
            String value = matched;
            if (rule.textFilter() != null) {
                Matcher filter = rule.textFilter().matcher(matched);
                if (filter.find()) {
                    value = filter.group();
                }
            }
            String key = addToken(tokens, customComponent(sender, rule.display(), value));
            output.append('<').append(key).append('>');
            end = matcher.end();
        } while (matcher.find());
        output.append(source, end, source.length());
        return output.toString();
    }

    private Component customComponent(
            Player sender,
            CustomFunctionSettings.Display display,
            String value
    ) {
        return deserializeTemplate(customTemplate(sender, display.text(), value));
    }

    private String customTemplate(Player sender, String template, String value) {
        String expanded = papi == null ? template : papi.expand(sender, template == null ? "" : template);
        String normalized = MessageColorParser.convert(expanded == null ? "" : expanded);
        return normalized.replace("{0}", miniMessage.escapeTags(value));
    }

    private Component deserializeTemplate(String source) {
        return deserializeTemplate(source, TagResolver.empty());
    }

    private Component deserializeTemplate(String source, TagResolver resolver) {
        try {
            return miniMessage.deserialize(MessageColorParser.convert(source), resolver);
        } catch (RuntimeException exception) {
            return Component.text(source);
        }
    }

    private String replaceSimpleShowcase(
            Player sender,
            FunctionSettings.Showcase config,
            Pattern pattern,
            String source,
            List<Token> tokens,
            Map<String, String> wireShowcases,
            ShowcaseStore.Kind kind
    ) {
        if (!config.enabled() || hasPermission(sender, config.permission()) || config.matches(source)) {
            return source;
        }
        Matcher matcher = pattern == null ? null : pattern.matcher(source);
        if (matcher == null || !matcher.find()
                || acquire(sender, kind == ShowcaseStore.Kind.INVENTORY ? "inventory" : "enderchest",
                config.cooldownMillis(), kind == ShowcaseStore.Kind.INVENTORY
                        ? "inventory-cooldown" : "enderchest-cooldown")) {
            return source;
        }
        ShowcaseStore.Showcase snapshot = kind == ShowcaseStore.Kind.INVENTORY
                ? inventorySnapshot(sender)
                : enderChestSnapshot(sender);
        String token = showcases.put(snapshot);
        wireShowcases.put(token, ShowcaseStore.serialize(snapshot));
        Component component = switch (kind) {
            case INVENTORY -> messages.component("function.inventory-format", Map.of("player", sender.getName()));
            case ENDER_CHEST -> messages.component("function.enderchest-format", Map.of("player", sender.getName()));
            case ITEM, CONTAINER -> throw new IllegalStateException("Unexpected showcase kind");
        };
        if (config.ui()) {
            String command = kind == ShowcaseStore.Kind.INVENTORY ? "/view-inventory " : "/view-enderchest ";
            component = component
                    .hoverEvent(HoverEvent.showText(messages.component(
                            kind == ShowcaseStore.Kind.INVENTORY ? "function.inventory-hover" : "function.enderchest-hover",
                            Map.of("player", sender.getName()))))
                    .clickEvent(ClickEvent.runCommand(command + token));
        }
        String key = addToken(tokens, component);
        StringBuilder output = new StringBuilder(source.length() + 16);
        int end = 0;
        do {
            output.append(source, end, matcher.start());
            output.append('<').append(key).append('>');
            end = matcher.end();
        } while (matcher.find());
        output.append(source, end, source.length());
        return output.toString();
    }

    private String replaceContainer(
            Player sender,
            FunctionSettings.Showcase config,
            Pattern pattern,
            String source,
            List<Token> tokens,
            Map<String, String> wireShowcases
    ) {
        if (!config.enabled() || hasPermission(sender, config.permission()) || config.matches(source)) {
            return source;
        }
        Matcher matcher = pattern == null ? null : pattern.matcher(source);
        if (matcher == null || !matcher.find()) {
            return source;
        }
        Entity entity = targetContainerEntity(sender, config.range());
        Block block = entity == null ? sender.getTargetBlockExact(config.range()) : null;
        Inventory inventory = entity == null ? block == null ? null : containerInventory(block)
                : containerInventory(entity);
        if (inventory == null) {
            Component component = messages.component("function.item-air");
            String key = addToken(tokens, component);
            StringBuilder output = new StringBuilder(source.length() + 16);
            int end = 0;
            do {
                output.append(source, end, matcher.start());
                output.append('<').append(key).append('>');
                end = matcher.end();
            } while (matcher.find());
            output.append(source, end, source.length());
            return output.toString();
        }
        if (acquire(sender, "container", config.cooldownMillis(), "container-cooldown")) {
            return source;
        }

        String containerName = block == null
                ? entityContainerName(entity)
                : craftEngine == null ? null : craftEngine.containerTitle(block);
        if (containerName == null && block != null
                && (craftEngine == null || !craftEngine.isCustomBlock(block))) {
            containerName = "<lang:" + block.getType().translationKey() + ">";
        }
        if (containerName == null) {
            containerName = messages.text("function.container-unknown", Map.of());
        }
        ShowcaseStore.Showcase snapshot = containerSnapshot(sender, inventory, containerName);
        String token = showcases.put(snapshot);
        wireShowcases.put(token, ShowcaseStore.serialize(snapshot));
        TagResolver containerNameResolver = Placeholder.component(
                "fx_container_name", deserializeTemplate(containerName));
        String format = messages.text("function.container-format", Map.of())
                .replace("{container}", "<fx_container_name>");
        Component component = deserializeTemplate(format, containerNameResolver);
        if (config.ui()) {
            String hover = messages.text("function.container-hover", Map.of())
                    .replace("{container}", "<fx_container_name>");
            component = component
                    .hoverEvent(HoverEvent.showText(deserializeTemplate(hover, containerNameResolver)))
                    .clickEvent(ClickEvent.runCommand("/view-container " + token));
        }
        String key = addToken(tokens, component);
        StringBuilder output = new StringBuilder(source.length() + 16);
        int end = 0;
        do {
            output.append(source, end, matcher.start());
            output.append('<').append(key).append('>');
            end = matcher.end();
        } while (matcher.find());
        output.append(source, end, source.length());
        return output.toString();
    }

    private Component itemComponent(
            Player sender,
            ItemStack item,
            FunctionSettings.Showcase config,
            Map<String, String> wireShowcases
    ) {
        ItemStack shown = config.compatible() ? new ItemStack(item.getType(), item.getAmount()) : item;
        Component itemName = itemName(item);
        TagResolver itemNameResolver = Placeholder.component("fx_item_name", itemName);
        String format = messages.text("function.item-format", Map.of("amount", shown.getAmount()))
                .replace("{item}", "<fx_item_name>");
        String hover = messages.text("function.item-hover", Map.of("amount", shown.getAmount()))
                .replace("{item}", "<fx_item_name>");
        Component component = deserializeTemplate(format, itemNameResolver)
                .hoverEvent(HoverEvent.showText(deserializeTemplate(hover, itemNameResolver)));
        if (config.ui()) {
            ShowcaseStore.Showcase snapshot = new ShowcaseStore.Showcase(
                    ShowcaseStore.Kind.ITEM,
                    messages.text("function.item-title", Map.of("player", sender.getName())),
                    new ItemStack[]{shown}
            );
            String token = showcases.put(snapshot);
            wireShowcases.put(token, ShowcaseStore.serialize(snapshot));
            component = component.clickEvent(ClickEvent.runCommand("/view-item " + token));
        }
        return component;
    }

    private ShowcaseStore.Showcase inventorySnapshot(Player sender) {
        PlayerInventory inventory = sender.getInventory();
        ItemStack[] result = new ItemStack[54];
        ItemStack[] storage = inventory.getStorageContents();
        System.arraycopy(storage, 0, result, 0, Math.min(storage.length, 36));
        ItemStack[] armor = inventory.getArmorContents();
        System.arraycopy(armor, 0, result, 45, Math.min(armor.length, 4));
        result[49] = inventory.getItemInOffHand();
        return new ShowcaseStore.Showcase(
                ShowcaseStore.Kind.INVENTORY,
                messages.text("function.inventory-title", Map.of("player", sender.getName())),
                result,
                inventory.getHeldItemSlot(),
                sender.getName()
        );
    }

    private ShowcaseStore.Showcase enderChestSnapshot(Player sender) {
        return new ShowcaseStore.Showcase(
                ShowcaseStore.Kind.ENDER_CHEST,
                messages.text("function.enderchest-title", Map.of("player", sender.getName())),
                sender.getEnderChest().getContents(),
                -1,
                sender.getName()
        );
    }

    private Inventory containerInventory(Block block) {
        if (craftEngine != null) {
            Inventory inventory = craftEngine.inventory(block);
            if (inventory != null) {
                return inventory;
            }
        }
        if (block.getState() instanceof Container container) {
            return container.getInventory();
        }
        return null;
    }

    private static Inventory containerInventory(Entity entity) {
        if (entity instanceof ChestedHorse horse && !horse.isCarryingChest()) {
            return null;
        }
        return entity instanceof InventoryHolder holder ? holder.getInventory() : null;
    }

    private static Entity targetContainerEntity(Player player, int range) {
        Location eye = player.getEyeLocation();
        RayTraceResult result = player.getWorld().rayTrace(
                eye,
                eye.getDirection(),
                range,
                FluidCollisionMode.NEVER,
                false,
                0.35,
                entity -> entity != player && isContainerEntity(entity)
        );
        return result == null ? null : result.getHitEntity();
    }

    private static boolean isContainerEntity(Entity entity) {
        return entity instanceof StorageMinecart
                || entity instanceof HopperMinecart
                || entity instanceof ChestBoat
                || entity instanceof ChestedHorse horse && horse.isCarryingChest();
    }

    private static String entityContainerName(Entity entity) {
        if (!isContainerEntity(entity)) {
            return null;
        }
        String translationKey = entity.getType().translationKey();
        return translationKey.isBlank()
                ? null : "<lang:" + translationKey + ">";
    }

    private ShowcaseStore.Showcase containerSnapshot(
            Player sender,
            Inventory container,
            String containerName
    ) {
        ItemStack[] items = container.getContents();
        return new ShowcaseStore.Showcase(
                ShowcaseStore.Kind.CONTAINER,
                containerName,
                items,
                -1,
                sender.getName(),
                container.getType()
        );
    }

    private Component mentionComponent(String name) {
        return messages.component("function.mention-format", Map.of("player", name))
                .hoverEvent(HoverEvent.showText(messages.component("function.mention-hover")))
                .clickEvent(ClickEvent.suggestCommand("/tell " + name + " "));
    }

    private Component mentionAllComponent() {
        return messages.component("function.mention-all-format")
                .hoverEvent(HoverEvent.showText(messages.component("function.mention-all-hover")));
    }

    private boolean acquire(Player player, String key, long duration, String cooldownMessage) {
        if (duration <= 0 || player.hasPermission("fxchat.bypass." + key + "cd")) {
            return false;
        }
        long now = System.currentTimeMillis();
        long[] remaining = {0L};
        boolean[] accepted = {false};
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .compute(key, (ignored, previous) -> {
                    if (previous == null || now - previous >= duration) {
                        accepted[0] = true;
                        return now;
                    }
                    remaining[0] = duration - (now - previous);
                    return previous;
                });
        if (!accepted[0]) {
            messages.sendActionBar(player, "function." + cooldownMessage,
                    Map.of("seconds", Math.max(1L, (remaining[0] + 999L) / 1_000L)));
        }
        return !accepted[0];
    }

    private static boolean hasPermission(Player player, String permission) {
        return permission != null && !permission.isBlank() && !permission.equalsIgnoreCase("none")
                && !player.hasPermission(permission);
    }

    private Pattern mentionPattern(
            FunctionSettings.Mention config,
            Map<String, PlayerSessionManager.OnlinePlayer> names,
            long directoryVersion
    ) {
        String template = config.pattern();
        MentionPatternCache cached = mentionPatternCache;
        if (cached.directoryVersion() == directoryVersion
                && Objects.equals(cached.template(), template)) {
            return cached.pattern();
        }
        synchronized (mentionPatternLock) {
            cached = mentionPatternCache;
            if (cached.directoryVersion() == directoryVersion
                    && Objects.equals(cached.template(), template)) {
                return cached.pattern();
            }
            String namesRegex = names.keySet().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote)
                    .reduce((left, right) -> left + "|" + right)
                    .orElse("");
            String configured = template == null ? "@(?<name>(names))" : template;
            String expression = configured.contains("(names)")
                    ? configured.replace("(names)", "(?<fxname>" + namesRegex + ")")
                    : "@(?<fxname>" + namesRegex + ")";
            Pattern pattern;
            try {
                pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (RuntimeException exception) {
                pattern = null;
            }
            mentionPatternCache = new MentionPatternCache(directoryVersion, template, pattern);
            return pattern;
        }
    }

    private static Pattern alternation(List<String> values) {
        String source = values.stream().filter(value -> value != null && !value.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElse("");
        if (source.isBlank()) {
            return null;
        }
        return Pattern.compile("(?:" + source + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static Pattern itemPattern(List<String> keys) {
        List<String> literalKeys = new ArrayList<>();
        List<String> numberedKeys = new ArrayList<>();
        literalKeys.add("%i%");
        literalKeys.add("%i");
        numberedKeys.add("%<num>");
        numberedKeys.add("%<num>%");
        for (String key : keys) {
            if (key == null || key.isBlank()
                    || key.equalsIgnoreCase("%i%")
                    || key.equalsIgnoreCase("%i")) {
                continue;
            }
            if (key.contains("<num>")) {
                numberedKeys.add(key);
            } else {
                literalKeys.add(key);
            }
        }
        literalKeys.sort(Comparator.comparingInt(String::length).reversed());

        List<String> alternatives = new ArrayList<>();
        for (String key : numberedKeys) {
            int placeholder = key.indexOf("<num>");
            alternatives.add(Pattern.quote(key.substring(0, placeholder))
                    + "([0-9])" + Pattern.quote(key.substring(placeholder + "<num>".length())));
        }
        for (String key : literalKeys) {
            String suffix = (key.equalsIgnoreCase("%i") || key.equalsIgnoreCase("%item")
                    || numberedKeys.stream().anyMatch(numbered -> numbered.startsWith(key)))
                    ? "(?![0-9-])" : "";
            alternatives.add(Pattern.quote(key) + suffix);
        }
        return Pattern.compile("(?:" + String.join("|", alternatives) + ")", Pattern.CASE_INSENSITIVE);
    }

    private static String matchedHotbarSlot(Matcher matcher) {
        for (int group = 1; group <= matcher.groupCount(); group++) {
            String value = matcher.group(group);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Pattern showcasePattern(List<String> keys) {
        String source = keys.stream().filter(key -> key != null && !key.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElse("");
        if (source.isBlank()) {
            return null;
        }
        return Pattern.compile("(?:" + source + ")", Pattern.CASE_INSENSITIVE);
    }

    private static ItemStack itemAtHotbar(PlayerInventory inventory, int slot) {
        return slot == 0 ? inventory.getItemInOffHand() : inventory.getItem(slot - 1);
    }

    private static Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                return displayName;
            }
        }
        if (meta != null && meta.hasItemName()) {
            return meta.itemName();
        }
        return Component.translatable(item.getType().translationKey());
    }

    private static String addToken(List<Token> tokens, Component component) {
        String key = "fxfunction_" + tokens.size();
        tokens.add(new Token(key, component));
        return key;
    }

    public record Token(String key, Component component) {
    }

    private record FunctionPatterns(
            Pattern mentionAll,
            Pattern item,
            Pattern inventory,
            Pattern enderChest,
            Pattern container
    ) {
        private static FunctionPatterns create(FunctionSettings settings) {
            return new FunctionPatterns(
                    alternation(settings.mentionAll().keys()),
                    itemPattern(settings.itemShow().keys()),
                    showcasePattern(settings.inventoryShow().keys()),
                    showcasePattern(settings.enderChestShow().keys()),
                    showcasePattern(settings.containerShow().keys())
            );
        }
    }

    private record FunctionState(FunctionSettings settings, FunctionPatterns patterns) {
    }

    private record MentionPatternCache(long directoryVersion, String template, Pattern pattern) {
    }

    public record PreparedMessage(
            String source,
            List<Token> tokens,
            Set<UUID> mentionedPlayers,
            boolean mentionAll,
            Map<String, String> showcases
    ) {
        public PreparedMessage {
            tokens = List.copyOf(tokens);
            mentionedPlayers = Set.copyOf(mentionedPlayers);
            showcases = Map.copyOf(showcases);
        }
    }

    private record MentionMatch(int start, int end, PlayerSessionManager.OnlinePlayer target) {
    }
}
