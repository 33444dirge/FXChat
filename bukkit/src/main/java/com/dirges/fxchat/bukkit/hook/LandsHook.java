package com.dirges.fxchat.bukkit.hook;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class LandsHook {
    private final LandsIntegration integration;

    private LandsHook(LandsIntegration integration) {
        this.integration = integration;
    }

    public static LandsHook create(Plugin plugin) {
        return new LandsHook(LandsIntegration.of(plugin));
    }

    public void leaveChat(Player player) {
        LandPlayer landPlayer = integration.getLandPlayer(player.getUniqueId());
        if (landPlayer != null && landPlayer.getChatMode() != null) {
            landPlayer.setChatMode(null);
        }
    }
}
