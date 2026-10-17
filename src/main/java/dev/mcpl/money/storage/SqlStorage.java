package dev.mcpl.money.storage;

import dev.mcpl.money.economy.Account;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.Transaction;
import dev.mcpl.money.economy.TransactionType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads and writes accounts and the transaction log with plain JDBC. Blocking; call it off the main thread. */
public final class SqlStorage implements AutoCloseable {

    private static final String UPSERT_ACCOUNT = """
            INSERT INTO money_accounts (uuid, name, balance, updated_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE
            SET name = excluded.name, balance = excluded.balance, updated_at = excluded.updated_at""";

    private static final String INSERT_TRANSACTION = """
            INSERT INTO money_transactions
                (created_at, type, from_uuid, from_name, to_uuid, to_name, amount, actor)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_HISTORY = """
            SELECT id, created_at, type, from_uuid, from_name, to_uuid, to_name, amount, actor
            FROM money_transactions
            WHERE from_uuid = ? OR to_uuid = ?
            ORDER BY id DESC
            LIMIT ? OFFSET ?""";

    private static final String COUNT_HISTORY =
            "SELECT COUNT(*) FROM money_transactions WHERE from_uuid = ? OR to_uuid = ?";

    private final DataSource dataSource;
    private final StorageType type;

    public SqlStorage(DataSource dataSource, StorageType type) {
        this.dataSource = dataSource;
        this.type = type;
    }

    public StorageType type() {
        return type;
    }

    public void createSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : type.schema()) {
                statement.execute(sql);
            }
        }
    }

    public List<Account> loadAccounts() throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT uuid, name, balance FROM money_accounts")) {
            while (rows.next()) {
                accounts.add(new Account(
                        UUID.fromString(rows.getString(1)),
                        rows.getString(2),
                        new BigDecimal(rows.getString(3))));
            }
        }
        return accounts;
    }

    /** Saves the changed accounts and the log entry in one database transaction. */
    public void apply(Economy.Change change) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Instant now = Instant.now();
                try (PreparedStatement upsert = connection.prepareStatement(UPSERT_ACCOUNT)) {
                    for (Account account : change.accounts()) {
                        upsert.setString(1, account.uuid().toString());
                        upsert.setString(2, account.name());
                        type.bindAmount(upsert, 3, account.balance());
                        type.bindTime(upsert, 4, now);
                        upsert.executeUpdate();
                    }
                }
                Transaction tx = change.transaction();
                if (tx != null) {
                    try (PreparedStatement insert = connection.prepareStatement(INSERT_TRANSACTION)) {
                        type.bindTime(insert, 1, tx.time());
                        insert.setString(2, tx.type().name());
                        insert.setString(3, uuidOrNull(tx.fromUuid()));
                        insert.setString(4, tx.fromName());
                        insert.setString(5, uuidOrNull(tx.toUuid()));
                        insert.setString(6, tx.toName());
                        type.bindAmount(insert, 7, tx.amount());
                        insert.setString(8, tx.actor());
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** Transactions where the player sent or received money, newest first. */
    public List<Transaction> history(UUID uuid, int limit, int offset) throws SQLException {
        List<Transaction> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(SELECT_HISTORY)) {
            query.setString(1, uuid.toString());
            query.setString(2, uuid.toString());
            query.setInt(3, limit);
            query.setInt(4, offset);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    list.add(new Transaction(
                            rows.getLong(1),
                            type.readTime(rows, 2),
                            TransactionType.valueOf(rows.getString(3)),
                            uuidFromNullable(rows.getString(4)),
                            rows.getString(5),
                            uuidFromNullable(rows.getString(6)),
                            rows.getString(7),
                            new BigDecimal(rows.getString(8)),
                            rows.getString(9)));
                }
            }
        }
        return list;
    }

    public int countHistory(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(COUNT_HISTORY)) {
            query.setString(1, uuid.toString());
            query.setString(2, uuid.toString());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** Closes the connection pool, if the data source is one. */
    @Override
    public void close() throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static String uuidOrNull(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private static UUID uuidFromNullable(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
