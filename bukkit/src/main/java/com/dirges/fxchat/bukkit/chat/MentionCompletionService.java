package com.dirges.fxchat.bukkit.chat;

import com.dirges.fxchat.bukkit.player.PlayerSessionManager;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps FXChat's @-mentions in the client's custom chat completion list. */
public final class MentionCompletionService implements AutoCloseable {
    private final SchedulerFacade scheduler;
    private final PlayerSessionManager sessions;
    private final Object lock = new Object();
    private final Set<String> advertisedNames = new LinkedHashSet<>();
    private volatile boolean mentionAllEnabled;
    private volatile List<String> mentionAllKeys;
    private volatile boolean closed;

    public MentionCompletionService(
            SchedulerFacade scheduler,
            PlayerSessionManager sessions,
            boolean mentionAllEnabled,
            List<String> mentionAllKeys
    ) {
        this.scheduler = scheduler;
        this.sessions = sessions;
        this.mentionAllEnabled = mentionAllEnabled;
        this.mentionAllKeys = List.copyOf(mentionAllKeys);
    }

    public void updateMentionAll(boolean enabled, List<String> keys) {
        mentionAllEnabled = enabled;
        mentionAllKeys = List.copyOf(keys);
        refresh();
    }

    public void refresh() {
        refreshFor(null);
    }

    public void refreshFor(UUID initializingPlayerId) {
        if (closed) {
            return;
        }
        Map<String, PlayerSessionManager.OnlinePlayer> online = sessions.onlineNameIndex();
        List<UUID> recipients = sessions.onlineIds();
        Set<String> next = new LinkedHashSet<>();
        online.values().stream()
                .map(PlayerSessionManager.OnlinePlayer::name)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> "@" + name)
                .forEach(next::add);
        if (mentionAllEnabled) {
            mentionAllKeys.stream()
                    .filter(key -> key != null && !key.isBlank())
                    .forEach(next::add);
        }

        synchronized (lock) {
            if (closed) {
                return;
            }
            Set<String> added = difference(next, advertisedNames);
            Set<String> removed = difference(advertisedNames, next);
            advertisedNames.clear();
            advertisedNames.addAll(next);

            if (added.isEmpty() && removed.isEmpty() && initializingPlayerId == null) {
                return;
            }

            for (UUID playerId : recipients) {
                boolean initialize = initializingPlayerId != null && initializingPlayerId.equals(playerId);
                Set<String> additions = initialize ? next : added;
                String ownName = sessions.localName(playerId);
                additions = withoutSelf(additions, ownName);

                List<String> playerAdditions = List.copyOf(additions);
                List<String> playerRemovals = List.copyOf(removed);
                if (playerAdditions.isEmpty() && playerRemovals.isEmpty()) {
                    continue;
                }
                sessions.runAt(playerId, scheduler, player -> {
                    if (closed || !player.isOnline()) {
                        return;
                    }
                    player.removeCustomChatCompletions(List.of("@" + player.getName()));
                    if (!playerRemovals.isEmpty()) {
                        player.removeCustomChatCompletions(playerRemovals);
                    }
                    if (!playerAdditions.isEmpty()) {
                        player.addCustomChatCompletions(playerAdditions);
                    }
                });
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            advertisedNames.clear();
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new LinkedHashSet<>(left);
        difference.removeAll(right);
        return Set.copyOf(difference);
    }

    private static Set<String> withoutSelf(Set<String> values, String name) {
        if (name == null || name.isBlank()) {
            return values;
        }
        String self = "@" + name;
        if (!values.contains(self)) {
            return values;
        }
        Set<String> result = new LinkedHashSet<>(values);
        result.remove(self);
        return Set.copyOf(result);
    }
}
