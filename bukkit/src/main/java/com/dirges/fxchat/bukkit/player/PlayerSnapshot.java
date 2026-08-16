package com.dirges.fxchat.bukkit.player;

import java.util.UUID;

public record PlayerSnapshot(UUID id, String name, UUID worldId, double x, double y, double z) {
}
