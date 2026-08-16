package com.dirges.fxchat.bukkit.protocol;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SeenMessages {
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_AGE_MILLIS = 120_000L;

    private final ConcurrentHashMap<UUID, Long> messages = new ConcurrentHashMap<>();

    public boolean markIfNew(UUID messageId) {
        long now = System.currentTimeMillis();
        cleanup(now);
        return messages.putIfAbsent(messageId, now) == null;
    }

    public void clear() {
        messages.clear();
    }

    private void cleanup(long now) {
        if (messages.size() <= MAX_ENTRIES) {
            return;
        }
        messages.entrySet().removeIf(entry -> now - entry.getValue() > MAX_AGE_MILLIS);
        if (messages.size() > MAX_ENTRIES) {
            UUID first = messages.keySet().stream().findFirst().orElse(null);
            if (first != null) {
                messages.remove(first);
            }
        }
    }
}
