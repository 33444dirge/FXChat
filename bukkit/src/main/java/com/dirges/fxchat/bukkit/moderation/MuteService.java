package com.dirges.fxchat.bukkit.moderation;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MuteService implements AutoCloseable {
    private static final Pattern DURATION = Pattern.compile(
            "^(\\d+)(秒|分|分钟|小时|时|天|周|s|m|h|d|w)?$", Pattern.CASE_INSENSITIVE);
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;
    private static final long EXPIRED_CLEANUP_INTERVAL_TICKS = 20L * 60L;

    private final File dataFolder;
    private final DatabaseSettings database;
    private final SchedulerFacade scheduler;
    private final Consumer<String> warning;
    private final ConcurrentHashMap<UUID, MuteRecord> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MuteRecord> pendingRemote = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cleanupWriteInFlight = new AtomicBoolean();

    private volatile SchedulerFacade.CancellableTask expiredCleanupTask = SchedulerFacade.CancellableTask.NOOP;

    public MuteService(
            File dataFolder,
            DatabaseSettings database,
            SchedulerFacade scheduler,
            Consumer<String> warning
    ) {
        this.dataFolder = dataFolder;
        this.database = database;
        this.scheduler = scheduler;
        this.warning = warning;
    }

    public void start() {
        expiredCleanupTask = scheduler.runGlobalAtFixedRate(
                this::cleanupExpiredRecords,
                EXPIRED_CLEANUP_INTERVAL_TICKS,
                EXPIRED_CLEANUP_INTERVAL_TICKS
        );
        scheduler.runAsync(() -> {
            if (closed.get()) {
                return;
            }
            try (Connection connection = database.open(dataFolder)) {
                createSchema(connection);
                deleteExpired(connection, System.currentTimeMillis());
                loadActive(connection, System.currentTimeMillis());
                for (MuteRecord record : pendingRemote.values()) {
                    saveRecord(connection, record);
                }
                if (closed.get()) {
                    active.clear();
                    pendingRemote.clear();
                    return;
                }
                pendingRemote.clear();
                ready.set(true);
            } catch (SQLException exception) {
                warning.accept("FXChat mute database initialization failed: " + exception.getMessage());
            }
        });
    }

    public boolean ready() {
        return !ready.get() || closed.get();
    }

    public MuteRecord active(UUID playerId) {
        if (closed.get()) {
            return null;
        }
        MuteRecord record = active.get(playerId);
        if (record != null && record.activeAt(System.currentTimeMillis())
                && active.remove(playerId, record)) {
            scheduler.runAsync(() -> deleteRecord(playerId));
            return null;
        }
        return record;
    }

    public void save(
            MuteRecord record,
            Consumer<MuteRecord> success,
            Consumer<Throwable> failure
    ) {
        if (ready()) {
            failure.accept(new IllegalStateException("Mute database is not ready"));
            return;
        }
        scheduler.runAsync(() -> {
            try (Connection connection = database.open(dataFolder)) {
                saveRecord(connection, record);
                if (closed.get()) {
                    return;
                }
                active.put(record.playerId(), record);
                success.accept(record);
            } catch (Throwable exception) {
                failure.accept(exception);
            }
        });
    }

    public void applyRemote(MuteRecord record) {
        if (closed.get() || record.activeAt(System.currentTimeMillis())) {
            return;
        }
        active.put(record.playerId(), record);
        if (ready()) {
            pendingRemote.put(record.playerId(), record);
            return;
        }
        scheduler.runAsync(() -> {
            try (Connection connection = database.open(dataFolder)) {
                saveRecord(connection, record);
            } catch (SQLException exception) {
                warning.accept("Could not persist remote mute for " + record.playerName()
                        + ": " + exception.getMessage());
            }
        });
    }

    public static DurationSpec parseDuration(String raw, long now) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.equals("永久") || value.equals("永远") || value.equals("perm")
                || value.equals("permanent") || value.equals("forever") || value.equals("0")) {
            return new DurationSpec(0L, "永久");
        }
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            long multiplier = unit == null || unit.equals("s") || unit.equals("秒")
                    ? SECOND
                    : unit.equals("m") || unit.equals("分") || unit.equals("分钟") ? MINUTE
                    : unit.equals("h") || unit.equals("时") || unit.equals("小时") ? HOUR
                    : unit.equals("d") || unit.equals("天") ? DAY : WEEK;
            long expiresAt = calculateExpiresAt(now, amount, multiplier);
            if (expiresAt <= now) {
                return null;
            }
            return new DurationSpec(expiresAt, value);
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static long calculateExpiresAt(long now, long amount, long multiplier) {
        return Math.addExact(now, Math.multiplyExact(amount, multiplier));
    }

    public static String remaining(MuteRecord record, long now) {
        if (record.expiresAt() == 0) {
            return "永久";
        }
        long remainingMillis = Math.max(0L, record.expiresAt() - now);
        long seconds = Math.max(1L, remainingMillis / SECOND
                + (remainingMillis % SECOND == 0 ? 0L : 1L));
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append('d');
        }
        if (hours > 0) {
            appendPart(result, hours, 'h');
        }
        if (minutes > 0) {
            appendPart(result, minutes, 'm');
        }
        if (seconds > 0 || result.isEmpty()) {
            appendPart(result, seconds, 's');
        }
        return result.toString();
    }

    private static void appendPart(StringBuilder result, long value, char unit) {
        if (!result.isEmpty()) {
            result.append(' ');
        }
        result.append(value).append(unit);
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS fxchat_mutes ("
                    + "player_uuid VARCHAR(36) PRIMARY KEY,"
                    + "player_name VARCHAR(64) NOT NULL,"
                    + "reason VARCHAR(512) NOT NULL,"
                    + "muted_by VARCHAR(64) NOT NULL,"
                    + "muted_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NOT NULL"
                    + ")");
        }
    }

    private void loadActive(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, player_name, reason, muted_by, muted_at, expires_at "
                        + "FROM fxchat_mutes WHERE expires_at = 0 OR expires_at > ?")) {
            statement.setLong(1, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    MuteRecord record = readRecord(result);
                    active.put(record.playerId(), record);
                }
            }
        }
    }

    private void deleteExpired(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM fxchat_mutes WHERE expires_at <> 0 AND expires_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    private void saveRecord(Connection connection, MuteRecord record) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM fxchat_mutes WHERE player_uuid = ?")) {
                delete.setString(1, record.playerId().toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO fxchat_mutes "
                            + "(player_uuid, player_name, reason, muted_by, muted_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, record.playerId().toString());
                insert.setString(2, record.playerName());
                insert.setString(3, record.reason());
                insert.setString(4, record.mutedBy());
                insert.setLong(5, record.mutedAt());
                insert.setLong(6, record.expiresAt());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void deleteRecord(UUID playerId) {
        if (ready() || closed.get()) {
            return;
        }
        try (Connection connection = database.open(dataFolder);
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM fxchat_mutes WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            warning.accept("Could not remove expired mute for " + playerId + ": " + exception.getMessage());
        }
    }

    private void cleanupExpiredRecords() {
        if (closed.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        active.entrySet().removeIf(entry -> entry.getValue().activeAt(now));
        if (!ready.get() || !cleanupWriteInFlight.compareAndSet(false, true)) {
            return;
        }
        scheduler.runAsync(() -> {
            try (Connection connection = database.open(dataFolder)) {
                deleteExpired(connection, now);
            } catch (SQLException exception) {
                if (!closed.get()) {
                    warning.accept("Could not remove expired mutes: " + exception.getMessage());
                }
            } finally {
                cleanupWriteInFlight.set(false);
            }
        });
    }

    private static MuteRecord readRecord(ResultSet result) throws SQLException {
        return new MuteRecord(
                UUID.fromString(result.getString("player_uuid")),
                result.getString("player_name"),
                result.getString("reason"),
                result.getString("muted_by"),
                result.getLong("muted_at"),
                result.getLong("expires_at")
        );
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            expiredCleanupTask.cancel();
            expiredCleanupTask = SchedulerFacade.CancellableTask.NOOP;
            ready.set(false);
            active.clear();
            pendingRemote.clear();
        }
    }

    public record DurationSpec(long expiresAt, String display) {
    }
}
