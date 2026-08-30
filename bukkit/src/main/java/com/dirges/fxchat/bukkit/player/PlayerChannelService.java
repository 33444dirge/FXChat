package com.dirges.fxchat.bukkit.player;

import com.dirges.fxchat.bukkit.config.DatabaseSettings;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Persists the last public channel selected by each player. */
public final class PlayerChannelService implements AutoCloseable {
    private final File dataFolder;
    private final DatabaseSettings database;
    private final SchedulerFacade scheduler;
    private final Consumer<String> warning;
    private final ConcurrentHashMap<UUID, String> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PlayerChannelService(File dataFolder, DatabaseSettings database, SchedulerFacade scheduler, Consumer<String> warning) {
        this.dataFolder = dataFolder;
        this.database = database;
        this.scheduler = scheduler;
        this.warning = warning;
    }

    public void start(PlayerSessionManager sessions) {
        scheduler.runAsync(() -> {
            try (Connection connection = database.open(dataFolder)) {
                createSchema(connection);
                load(connection);
                restoreInto(sessions);
            } catch (SQLException exception) {
                warning.accept("FXChat player-channel database initialization failed: " + exception.getMessage());
            }
        });
    }

    public void restoreInto(PlayerSessionManager sessions) {
        channels.forEach(sessions::restoreChannel);
    }

    public void restore(UUID playerId, PlayerSessionManager sessions) {
        String channel = channels.get(playerId);
        if (channel != null) sessions.restoreChannel(playerId, channel);
    }

    public void save(UUID playerId, String channel) {
        if (closed.get()) return;
        channels.put(playerId, channel);
        scheduler.runAsync(() -> {
            try (Connection connection = database.open(dataFolder)) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM fxchat_player_channels WHERE player_uuid = ?");
                     PreparedStatement insert = connection.prepareStatement(
                             "INSERT INTO fxchat_player_channels (player_uuid, channel_id) VALUES (?, ?)")) {
                    delete.setString(1, playerId.toString());
                    delete.executeUpdate();
                    insert.setString(1, playerId.toString());
                    insert.setString(2, channel);
                    insert.executeUpdate();
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException exception) {
                warning.accept("Could not save player channel for " + playerId + ": " + exception.getMessage());
            }
        });
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS fxchat_player_channels ("
                    + "player_uuid VARCHAR(36) PRIMARY KEY, channel_id VARCHAR(128) NOT NULL)");
        }
    }

    private void load(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT player_uuid, channel_id FROM fxchat_player_channels")) {
            while (result.next()) {
                try {
                    channels.put(UUID.fromString(result.getString("player_uuid")), result.getString("channel_id"));
                } catch (IllegalArgumentException exception) {
                    warning.accept("Ignoring invalid player-channel UUID in database.");
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) channels.clear();
    }
}
