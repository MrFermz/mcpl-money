package dev.mcpl.money.storage;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The supported databases. Queries are shared; only the table definitions and the way amounts
 * and timestamps are bound differ. Every table has an {@code id} primary key. Keep the schema
 * here in sync with the "Database schema" section of README.md.
 */
public enum StorageType {

    SQLITE("org.sqlite.JDBC", List.of(
            """
            CREATE TABLE IF NOT EXISTS money_accounts (
                id         INTEGER  PRIMARY KEY AUTOINCREMENT,
                uuid       TEXT     NOT NULL UNIQUE,
                name       TEXT     NOT NULL,
                balance    TEXT     NOT NULL,
                updated_at DATETIME NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS money_transactions (
                id         INTEGER  PRIMARY KEY AUTOINCREMENT,
                created_at DATETIME NOT NULL,
                type       TEXT     NOT NULL,
                from_uuid  TEXT,
                from_name  TEXT,
                to_uuid    TEXT,
                to_name    TEXT,
                amount     TEXT     NOT NULL,
                actor      TEXT     NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS money_transactions_from_idx ON money_transactions (from_uuid, id)",
            "CREATE INDEX IF NOT EXISTS money_transactions_to_idx ON money_transactions (to_uuid, id)")) {

        /** SQLite has no exact decimal type, so amounts are stored as text. */
        @Override
        void bindAmount(PreparedStatement statement, int index, BigDecimal amount) throws SQLException {
            statement.setString(index, amount.toPlainString());
        }

        /**
         * SQLite has no date type. Timestamps are stored as UTC text in the format SQLite's own date
         * functions use (e.g. {@code 2026-01-31 13:45:00.123}), so {@code datetime()} and
         * {@code strftime()} work on them and they sort correctly.
         */
        @Override
        void bindTime(PreparedStatement statement, int index, Instant time) throws SQLException {
            statement.setString(index, SQLITE_TIME.format(LocalDateTime.ofInstant(time, ZoneOffset.UTC)));
        }

        @Override
        Instant readTime(ResultSet rows, int index) throws SQLException {
            return LocalDateTime.parse(rows.getString(index), SQLITE_TIME).toInstant(ZoneOffset.UTC);
        }
    },

    POSTGRESQL("org.postgresql.Driver", List.of(
            """
            CREATE TABLE IF NOT EXISTS money_accounts (
                id         BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                uuid       VARCHAR(36)    NOT NULL UNIQUE,
                name       VARCHAR(32)    NOT NULL,
                balance    NUMERIC(38, 8) NOT NULL,
                updated_at TIMESTAMPTZ    NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS money_transactions (
                id         BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                created_at TIMESTAMPTZ    NOT NULL,
                type       VARCHAR(16)    NOT NULL,
                from_uuid  VARCHAR(36),
                from_name  VARCHAR(32),
                to_uuid    VARCHAR(36),
                to_name    VARCHAR(32),
                amount     NUMERIC(38, 8) NOT NULL,
                actor      VARCHAR(64)    NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS money_transactions_from_idx ON money_transactions (from_uuid, id)",
            "CREATE INDEX IF NOT EXISTS money_transactions_to_idx ON money_transactions (to_uuid, id)")) {

        @Override
        void bindAmount(PreparedStatement statement, int index, BigDecimal amount) throws SQLException {
            statement.setBigDecimal(index, amount);
        }

        @Override
        void bindTime(PreparedStatement statement, int index, Instant time) throws SQLException {
            statement.setObject(index, time.atOffset(ZoneOffset.UTC));
        }

        @Override
        Instant readTime(ResultSet rows, int index) throws SQLException {
            return rows.getObject(index, OffsetDateTime.class).toInstant();
        }
    };

    private static final DateTimeFormatter SQLITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final String driverClass;
    private final List<String> schema;

    StorageType(String driverClass, List<String> schema) {
        this.driverClass = driverClass;
        this.schema = schema;
    }

    public String driverClass() {
        return driverClass;
    }

    /** {@code CREATE ... IF NOT EXISTS} statements, safe to run on every start. */
    public List<String> schema() {
        return schema;
    }

    abstract void bindAmount(PreparedStatement statement, int index, BigDecimal amount) throws SQLException;

    abstract void bindTime(PreparedStatement statement, int index, Instant time) throws SQLException;

    abstract Instant readTime(ResultSet rows, int index) throws SQLException;

    /**
     * Parses {@code storage.type} from config.yml.
     *
     * @throws IllegalArgumentException for anything other than sqlite, postgresql or postgres
     */
    public static StorageType fromConfig(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> SQLITE;
            case "postgresql", "postgres" -> POSTGRESQL;
            default -> throw new IllegalArgumentException(
                    "Unknown storage.type '" + value + "', expected sqlite or postgresql");
        };
    }
}
