package com.dirges.fxchat.bukkit.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Local channel partitioned by a stable group key, such as an island UUID.
 * Resolver must be thread-safe: it runs on sender and recipient entity threads.
 * Return null to deny access. Do not access another player's entity/world state.
 */
public record CustomChannel(String id, List<String> commands, String permission,
                            String format, Function<UUID, String> groupResolver) {
    public CustomChannel {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Channel id is required");
        commands = List.copyOf(commands);
        for (String command : commands) {
            if (!command.matches("[a-zA-Z0-9_-]+"))
                throw new IllegalArgumentException("Invalid channel command: " + command);
        }
        permission = Objects.requireNonNull(permission);
        format = Objects.requireNonNull(format);
        Objects.requireNonNull(groupResolver);
    }
}
