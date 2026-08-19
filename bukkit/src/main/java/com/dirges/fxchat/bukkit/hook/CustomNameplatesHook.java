package com.dirges.fxchat.bukkit.hook;

import net.momirealms.customnameplates.api.CNPlayer;
import net.momirealms.customnameplates.api.CustomNameplatesAPI;
import net.momirealms.customnameplates.api.feature.chat.ChatManager;
import net.momirealms.customnameplates.api.feature.chat.ChatMessageProvider;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/** Routes only public FXChat messages to CustomNameplates; private messages never create bubbles. */
public final class CustomNameplatesHook implements AutoCloseable {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String ZERO_WIDTH_SPACE = "\u200B";

    private final CustomNameplatesAPI api;
    private final ChatManager manager;
    private volatile boolean closed;

    private CustomNameplatesHook(CustomNameplatesAPI api, ChatManager manager) {
        this.api = api;
        this.manager = manager;
    }

    public static CustomNameplatesHook create() {
        CustomNameplatesAPI api = CustomNameplatesAPI.getInstance();
        ChatManager manager = api.plugin().getChatManager();
        if (manager == null || !manager.setCustomChatProvider(new PublicChatProvider())) {
            throw new IllegalStateException("CustomNameplates chat provider is already customized");
        }
        return new CustomNameplatesHook(api, manager);
    }

    public void onPublicChat(Player player, String message, String channel, boolean allowMiniMessage, boolean allowLegacy) {
        if (closed || message == null || channel == null) {
            return;
        }
        CNPlayer customPlayer = api.getPlayer(player.getUniqueId());
        if (customPlayer != null && customPlayer.isOnline()) {
            manager.onChat(customPlayer, bubbleText(message, allowMiniMessage, allowLegacy), channel);
        }
    }

    private static String bubbleText(String message, boolean allowMiniMessage, boolean allowLegacy) {
        String result = allowMiniMessage ? message : MINI_MESSAGE.escapeTags(message);
        // ChatManager exposes only String input, so prevent it from interpreting unauthorized legacy codes.
        return allowLegacy ? result : result.replace("&", "&" + ZERO_WIDTH_SPACE);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            manager.removeCustomChatProvider();
        }
    }

    private static final class PublicChatProvider implements ChatMessageProvider {
        @Override
        public boolean hasJoinedChannel(CNPlayer player, String channelId) {
            return true;
        }

        @Override
        public boolean canJoinChannel(CNPlayer player, String channelId) {
            return true;
        }

        @Override
        public boolean isIgnoring(CNPlayer sender, CNPlayer receiver) {
            return false;
        }
    }
}
