package dev.mcpl.money.storage;

/** The {@code storage} section of config.yml. Only the fields for {@code type} are used. */
public record StorageSettings(
        StorageType type,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize) {
}
