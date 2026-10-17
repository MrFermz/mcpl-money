package dev.mcpl.money.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;

/** Builds the connection pool for the configured database. */
public final class DataSources {

    private DataSources() {
    }

    /** Settings for the pool, without opening any connection. */
    public static HikariConfig config(StorageSettings settings, Path dataFolder) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("mcpl-money");
        config.setDriverClassName(settings.type().driverClass());
        if (settings.type() == StorageType.SQLITE) {
            // One connection: SQLite allows a single writer, and all writes are already serialized.
            Path file = dataFolder.resolve(settings.sqliteFile()).toAbsolutePath();
            config.setJdbcUrl("jdbc:sqlite:" + file + "?journal_mode=WAL&busy_timeout=5000");
            config.setMaximumPoolSize(1);
        } else {
            config.setJdbcUrl("jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(Math.max(1, settings.poolSize()));
        }
        return config;
    }

    /** Opens the pool. Fails right away if the database can't be reached. */
    public static HikariDataSource open(StorageSettings settings, Path dataFolder) {
        return new HikariDataSource(config(settings, dataFolder));
    }
}
