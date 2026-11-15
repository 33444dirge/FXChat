package com.dirges.fxchat.bukkit.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Obtain via Bukkit.getServicesManager().load(FXChatApi.class).
 * Register/close on global thread; player operations on the player's entity thread.
 * Registrations survive config reloads and close when their owner disables.
 */
public interface FXChatApi {
    interface Registration extends AutoCloseable { @Override void close(); }
    Registration registerChannel(Plugin owner, CustomChannel channel);
    /** Idempotently enter; false if unknown or access is denied. */
    boolean enterChannel(Player player, String channel);
    boolean leaveChannel(Player player);
    String activeChannel(Player player);
}
