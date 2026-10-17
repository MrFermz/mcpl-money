package dev.mcpl.money.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mcpl.money.economy.Account;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.Transaction;
import dev.mcpl.money.economy.TransactionType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The same storage tests, run against SQLite and PostgreSQL by the subclasses. */
abstract class SqlStorageContract {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");

    SqlStorage storage;

    abstract DataSource dataSource();

    abstract StorageType type();

    @BeforeEach
    void createStorage() throws SQLException {
        execute("DROP TABLE IF EXISTS money_transactions");
        execute("DROP TABLE IF EXISTS money_accounts");
        storage = new SqlStorage(dataSource(), type());
        storage.createSchema();
    }

    void execute(String sql) throws SQLException {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    static Transaction tx(TransactionType type, UUID from, String fromName, UUID to, String toName, String amount) {
        return new Transaction(0, T1, type, from, fromName, to, toName, money(amount), "tester");
    }

    @Test
    void schemaCanBeCreatedTwiceAndStartsEmpty() throws SQLException {
        storage.createSchema();

        assertThat(storage.type()).isEqualTo(type());
        assertThat(storage.loadAccounts()).isEmpty();
        assertThat(storage.countHistory(A)).isZero();
        assertThat(storage.history(A, 10, 0)).isEmpty();
    }

    @Test
    void savesAccountAndTransaction() throws SQLException {
        Account alice = new Account(A, "Alice", money("100.00"));
        storage.apply(new Economy.Change(List.of(alice),
                tx(TransactionType.CREATE, null, null, A, "Alice", "100.00")));

        assertThat(storage.loadAccounts()).singleElement().satisfies(account -> {
            assertThat(account.uuid()).isEqualTo(A);
            assertThat(account.name()).isEqualTo("Alice");
            assertThat(account.balance()).isEqualByComparingTo("100");
        });
        assertThat(storage.history(A, 10, 0)).singleElement().satisfies(row -> {
            assertThat(row.id()).isPositive();
            assertThat(row.time()).isEqualTo(T1);
            assertThat(row.type()).isEqualTo(TransactionType.CREATE);
            assertThat(row.fromUuid()).isNull();
            assertThat(row.fromName()).isNull();
            assertThat(row.toUuid()).isEqualTo(A);
            assertThat(row.toName()).isEqualTo("Alice");
            assertThat(row.amount()).isEqualByComparingTo("100");
            assertThat(row.actor()).isEqualTo("tester");
        });
    }

    @Test
    void updatesExistingAccountAndKeepsExactAmounts() throws SQLException {
        storage.apply(new Economy.Change(List.of(new Account(A, "Alice", money("1"))), null));
        storage.apply(new Economy.Change(List.of(new Account(A, "Alicia", money("123456789012345.67"))), null));

        assertThat(storage.loadAccounts()).singleElement().satisfies(account -> {
            assertThat(account.name()).isEqualTo("Alicia");
            assertThat(account.balance()).isEqualByComparingTo("123456789012345.67");
        });
        assertThat(storage.countHistory(A)).isZero();
    }

    @Test
    void historyIncludesBothSidesNewestFirstWithPaging() throws SQLException {
        Account alice = new Account(A, "Alice", money("1"));
        Account bob = new Account(B, "Bob", money("1"));
        storage.apply(new Economy.Change(List.of(alice, bob), tx(TransactionType.PAY, A, "Alice", B, "Bob", "1")));
        storage.apply(new Economy.Change(List.of(bob), tx(TransactionType.GIVE, null, null, B, "Bob", "2")));
        storage.apply(new Economy.Change(List.of(alice), tx(TransactionType.TAKE, A, "Alice", null, null, "3")));
        storage.apply(new Economy.Change(List.of(bob, alice), tx(TransactionType.PAY, B, "Bob", A, "Alice", "4")));

        assertThat(storage.countHistory(A)).isEqualTo(3);
        assertThat(storage.countHistory(B)).isEqualTo(3);
        assertThat(storage.history(A, 10, 0)).extracting(t -> t.amount().intValue()).containsExactly(4, 3, 1);
        assertThat(storage.history(A, 2, 1)).extracting(t -> t.amount().intValue()).containsExactly(3, 1);
        assertThat(storage.history(B, 10, 0)).extracting(Transaction::type)
                .containsExactly(TransactionType.PAY, TransactionType.GIVE, TransactionType.PAY);
    }

    /** First column of the first row, as text. */
    String scalar(String sql) throws SQLException {
        try (Connection connection = dataSource().getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Test
    void everyTableHasIdPrimaryKeyAndAccountIdStaysTheSame() throws SQLException {
        storage.apply(new Economy.Change(List.of(new Account(A, "Alice", money("1")), new Account(B, "Bob", money("2"))),
                tx(TransactionType.PAY, A, "Alice", B, "Bob", "1")));
        String aliceId = scalar("SELECT id FROM money_accounts WHERE uuid = '" + A + "'");
        String bobId = scalar("SELECT id FROM money_accounts WHERE uuid = '" + B + "'");

        storage.apply(new Economy.Change(List.of(new Account(A, "Alicia", money("5"))), null));

        assertThat(aliceId).isNotEqualTo(bobId);
        assertThat(scalar("SELECT id FROM money_accounts WHERE uuid = '" + A + "'")).isEqualTo(aliceId);
        assertThat(scalar("SELECT COUNT(*) FROM money_accounts")).isEqualTo("2");
        assertThat(scalar("SELECT id FROM money_transactions")).isEqualTo("1");
    }

    @Test
    void timestampsKeepMilliseconds() throws SQLException {
        Instant time = Instant.parse("2026-07-08T09:10:11.123Z");
        storage.apply(new Economy.Change(List.of(new Account(A, "Alice", money("1"))),
                new Transaction(0, time, TransactionType.GIVE, null, null, A, "Alice", money("1"), "tester")));

        assertThat(storage.history(A, 1, 0).getFirst().time()).isEqualTo(time);
        assertStoredAsDate("2026-07-08 09:10:11.123");
    }

    /** Checks the database itself treats {@code created_at} and {@code updated_at} as dates. */
    abstract void assertStoredAsDate(String expectedCreatedAt) throws SQLException;

    @Test
    void failedTransactionRollsBackAccountChanges() throws SQLException {
        execute("DROP TABLE money_transactions");

        assertThatThrownBy(() -> storage.apply(new Economy.Change(List.of(new Account(A, "Alice", money("1"))),
                tx(TransactionType.GIVE, null, null, A, "Alice", "1"))))
                .isInstanceOf(SQLException.class);

        assertThat(storage.loadAccounts()).isEmpty();
    }
}
