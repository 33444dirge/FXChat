package com.dirges.fxchat.bukkit.moderation;

import com.dirges.fxchat.bukkit.config.DatabaseSettings;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Database-backed private-message spy subscriptions. */
public final class PrivateSpyService implements AutoCloseable {
    private final File dataFolder;
    private final DatabaseSettings database;
    private final SchedulerFacade scheduler;
    private final Consumer<String> warning;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Boolean> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PrivateSpyService(File dataFolder, DatabaseSettings database, SchedulerFacade scheduler, Consumer<String> warning) {
        this.dataFolder = dataFolder;
        this.database = database;
        this.scheduler = scheduler;
        this.warning = warning;
    }

    public void start() {
        scheduler.runAsync(() -> {
            try (Connection connection = database.open(dataFolder)) {
                createSchema(connection);
                load(connection);
                for (var entry : pending.entrySet()) {
                    persist(connection, entry.getKey(), entry.getValue());
                }
                pending.clear();
                ready.set(true);
            } catch (SQLException exception) {
                warning.accept("FXChat private spy database initialization failed: " + exception.getMessage());
            }
        });
    }

    public boolean toggle(UUID playerId) {
        return set(playerId, !enabled.contains(playerId));
    }

    public boolean set(UUID playerId, boolean value) {
        if (closed.get()) return false;
        if (value) enabled.add(playerId); else enabled.remove(playerId);
        if (!ready.get()) {
            pending.put(playerId, value);
        } else {
            scheduler.runAsync(() -> save(playerId, value));
        }
        return value;
    }

    public boolean isEmpty() { return enabled.isEmpty(); }
    public Set<UUID> enabled() { return Set.copyOf(enabled); }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS fxchat_private_spies (player_uuid VARCHAR(36) PRIMARY KEY)");
        }
    }

    private void load(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM fxchat_private_spies");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                try { enabled.add(UUID.fromString(result.getString(1))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void save(UUID playerId, boolean value) {
        if (closed.get()) return;
        try (Connection connection = database.open(dataFolder)) {
            persist(connection, playerId, value);
        } catch (SQLException exception) {
            warning.accept("Could not save private spy state for " + playerId + ": " + exception.getMessage());
        }
    }

    private static void persist(Connection connection, UUID playerId, boolean value) throws SQLException {
        String sql = value ? "INSERT INTO fxchat_private_spies (player_uuid) VALUES (?)" : "DELETE FROM fxchat_private_spies WHERE player_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (value) {
                try (PreparedStatement update = connection.prepareStatement("DELETE FROM fxchat_private_spies WHERE player_uuid = ?")) {
                    update.setString(1, playerId.toString());
                    update.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO fxchat_private_spies (player_uuid) VALUES (?)")) {
                    insert.setString(1, playerId.toString());
                    insert.executeUpdate();
                }
            } else throw exception;
        }
    }

    @Override public void close() { closed.set(true); enabled.clear(); pending.clear(); }
}
