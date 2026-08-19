package com.dirges.fxchat.bukkit.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Consumer;

public record DatabaseSettings(
        String type,
        String h2File,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        boolean mysqlSsl
) {
    public static DatabaseSettings load(File file, Consumer<String> warning) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String type = config.getString("type", "h2").trim().toLowerCase(Locale.ROOT);
        if (!type.equals("h2") && !type.equals("mysql")) {
            warning.accept("Unknown database type " + type + "; using h2.");
            type = "h2";
        }
        return new DatabaseSettings(
                type,
                config.getString("h2.file", "data/fxchat").trim(),
                config.getString("mysql.host", "127.0.0.1").trim(),
                Math.clamp(config.getInt("mysql.port", 3306), 1, 65535),
                config.getString("mysql.database", "fxchat").trim(),
                config.getString("mysql.username", "root").trim(),
                config.getString("mysql.password", ""),
                config.getBoolean("mysql.use-ssl", false)
        );
    }

    public Connection open(File dataFolder) throws SQLException {
        try {
            Class.forName(type.equals("mysql") ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("Database driver is missing for " + type, exception);
        }
        if (type.equals("mysql")) {
            String url = "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase
                    + "?useSSL=" + mysqlSsl
                    + "&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8"
                    + "&connectionCollation=utf8mb4_unicode_ci";
            return DriverManager.getConnection(url, mysqlUsername, mysqlPassword);
        }

        File database = new File(h2File.isBlank() ? "data/fxchat" : h2File);
        if (!database.isAbsolute()) {
            database = new File(dataFolder, database.getPath());
        }
        File parent = database.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new SQLException("Could not create H2 database directory: " + parent);
        }
        return DriverManager.getConnection(
                "jdbc:h2:file:" + database.getAbsolutePath().replace('\\', '/')
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE"
        );
    }
}
