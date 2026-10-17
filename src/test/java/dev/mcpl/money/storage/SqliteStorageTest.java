package dev.mcpl.money.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStorageTest extends SqlStorageContract {

    @TempDir
    Path dir;
    HikariDataSource dataSource;

    @AfterEach
    void close() throws Exception {
        storage.close();
    }

    @Override
    DataSource dataSource() {
        // Opened lazily: the base class @BeforeEach runs before any subclass @BeforeEach.
        if (dataSource == null) {
            dataSource = DataSources.open(
                    new StorageSettings(StorageType.SQLITE, "test.db", null, 0, null, null, null, 0), dir);
        }
        return dataSource;
    }

    @Override
    StorageType type() {
        return StorageType.SQLITE;
    }

    @Override
    void assertStoredAsDate(String expectedCreatedAt) throws java.sql.SQLException {
        assertThat(scalar("SELECT created_at FROM money_transactions")).isEqualTo(expectedCreatedAt);
        // SQLite's date functions understand the stored values.
        assertThat(scalar("SELECT datetime(created_at) FROM money_transactions")).isEqualTo(expectedCreatedAt.substring(0, 19));
        assertThat(scalar("SELECT datetime(updated_at) IS NOT NULL FROM money_accounts")).isEqualTo("1");
    }

    @Test
    void storesAmountsAsPlainText() throws Exception {
        storage.apply(new dev.mcpl.money.economy.Economy.Change(
                java.util.List.of(new dev.mcpl.money.economy.Account(A, "Alice", money("0.00000001"))), null));

        try (var connection = dataSource.getConnection();
             var rows = connection.createStatement().executeQuery("SELECT balance FROM money_accounts")) {
            rows.next();
            assertThat(rows.getString(1)).isEqualTo("0.00000001");
        }
    }

    @Test
    void closeClosesThePool() throws Exception {
        storage.close();

        assertThat(dataSource.isClosed()).isTrue();
    }
}
