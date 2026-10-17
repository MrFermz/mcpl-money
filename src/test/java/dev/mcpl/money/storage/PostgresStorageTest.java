package dev.mcpl.money.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs against a real PostgreSQL server that the test starts and stops itself. */
class PostgresStorageTest extends SqlStorageContract {

    static EmbeddedPostgres postgres;

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    @Override
    DataSource dataSource() {
        return postgres.getPostgresDatabase();
    }

    @Override
    StorageType type() {
        return StorageType.POSTGRESQL;
    }

    @Override
    void assertStoredAsDate(String expectedCreatedAt) throws java.sql.SQLException {
        assertThat(scalar("""
                SELECT string_agg(table_name || '.' || column_name || '=' || data_type, ',' ORDER BY table_name)
                FROM information_schema.columns WHERE column_name IN ('created_at', 'updated_at')"""))
                .isEqualTo("money_accounts.updated_at=timestamp with time zone,"
                        + "money_transactions.created_at=timestamp with time zone");
        assertThat(scalar("SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS') FROM money_transactions"))
                .isEqualTo(expectedCreatedAt);
    }

    @Test
    void idColumnsAreIdentityPrimaryKeys() throws java.sql.SQLException {
        assertThat(scalar("""
                SELECT string_agg(tc.table_name || '.' || kcu.column_name, ',' ORDER BY tc.table_name)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name LIKE 'money_%'"""))
                .isEqualTo("money_accounts.id,money_transactions.id");
        assertThat(scalar("""
                SELECT string_agg(table_name || '.' || column_name, ',' ORDER BY table_name)
                FROM information_schema.columns WHERE is_identity = 'YES' AND table_name LIKE 'money_%'"""))
                .isEqualTo("money_accounts.id,money_transactions.id");
    }

    @Test
    void closeLeavesNonPoolDataSourceAlone() throws Exception {
        storage.close();

        assertThat(storage.loadAccounts()).isEmpty();
    }
}
