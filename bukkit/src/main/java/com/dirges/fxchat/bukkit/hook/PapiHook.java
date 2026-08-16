package com.dirges.fxchat.bukkit.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

public final class PapiHook {
    public String expand(Player player, String value) {
        return PlaceholderAPI.setPlaceholders(player, value);
    }
}
