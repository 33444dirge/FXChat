package com.dirges.fxchat.bukkit.listener;

import com.dirges.fxchat.bukkit.chat.ChatService;
import com.dirges.fxchat.bukkit.chat.ChatFilterService;
import com.dirges.fxchat.bukkit.chat.MentionCompletionService;
import com.dirges.fxchat.bukkit.function.ShowcaseStore;
import com.dirges.fxchat.bukkit.hook.BlockLockerHook;
import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class FXChatListener implements Listener {
    private final SchedulerFacade scheduler;
    private final ChatService chatService;
    private final ChatFilterService filters;
    private final PlayerSessionManager sessions;
    private final MentionCompletionService mentionCompletions;
    private final BlockLockerHook blockLocker;

    public FXChatListener(
            SchedulerFacade scheduler,
            ChatService chatService,
            ChatFilterService filters,
            PlayerSessionManager sessions,
            MentionCompletionService mentionCompletions,
            BlockLockerHook blockLocker
    ) {
        this.scheduler = scheduler;
        this.chatService = chatService;
        this.filters = filters;
        this.sessions = sessions;
        this.mentionCompletions = mentionCompletions;
        this.blockLocker = blockLocker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // Let Paper complete signed-chat acknowledgement while FXChat delivers the message itself.
        event.viewers().clear();
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        scheduler.runAtEntity(player, () -> chatService.handleChat(player, message));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChannelCommand(PlayerCommandPreprocessEvent event) {
        String input = event.getMessage();
        if (input.length() < 2 || input.charAt(0) != '/') {
            return;
        }
        String commandLine = input.substring(1).stripLeading();
        if (commandLine.isEmpty()) {
            return;
        }
        String[] parts = commandLine.split("\\s+", 2);
        String alias = parts[0];
        if (alias.indexOf(':') >= 0) {
            return;
        }
        String channelId = chatService.channelAlias(alias);
        if (channelId == null) {
            return;
        }
        String message = parts.length > 1 ? parts[1] : "";
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            chatService.handleChannelAlias(player, channelId, message);
        } else {
            scheduler.runAtEntity(player, () -> chatService.handleChannelAlias(player, channelId, message));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessions.join(player);
        mentionCompletions.refreshFor(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.quit(event.getPlayer().getUniqueId());
        chatService.removePlayer(event.getPlayer().getUniqueId());
        mentionCompletions.refresh();
    }

    @EventHandler
    public void onShowcaseClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShowcaseStore.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onShowcaseDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShowcaseStore.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        boolean blockLockerProtection = blockLocker != null && blockLocker.isProtectionSign(event);
        for (int line = 0; line < 4; line++) {
            String original = event.getLine(line);
            if (blockLockerProtection && (line == 0 || isBlockLockerAccessEntry(original))) {
                continue;
            }
            String filtered = filters.filterSign(original);
            if (!original.equals(filtered)) {
                event.setLine(line, filtered);
            }
        }
    }

    private static boolean isBlockLockerAccessEntry(String text) {
        return text != null && !text.isEmpty() && text.chars().allMatch(character ->
                (character >= 'a' && character <= 'z')
                        || (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9')
                        || character == '_');
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        ItemMeta meta = result.getItemMeta();
        boolean changed = false;
        if (meta.hasDisplayName()) {
            Component name = meta.displayName();
            String original = PlainTextComponentSerializer.plainText().serialize(name);
            String filtered = filters.filterAnvil(original);
            if (!original.equals(filtered)) {
                meta.displayName(Component.text(filtered));
                changed = true;
            }
        }
        if (meta.hasItemName()) {
            Component name = meta.itemName();
            String original = PlainTextComponentSerializer.plainText().serialize(name);
            String filtered = filters.filterAnvil(original);
            if (!original.equals(filtered)) {
                meta.itemName(Component.text(filtered));
                changed = true;
            }
        }
        if (changed) {
            ItemStack filteredResult = result.clone();
            filteredResult.setItemMeta(meta);
            event.setResult(filteredResult);
        }
    }
}
