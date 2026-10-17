package dev.mcpl.money.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EconomyTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    final List<Economy.Change> changes = new ArrayList<>();
    Economy economy;

    @BeforeEach
    void setUp() {
        economy = new Economy(new Economy.Settings(2, new BigDecimal("100"), new BigDecimal("1000")),
                Clock.fixed(NOW, ZoneOffset.UTC), changes::add);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private void twoAccounts() {
        economy.ensureAccount(A, "Alice");
        economy.ensureAccount(B, "Bob");
        changes.clear();
    }

    @Test
    void settingsAreNormalizedToScale() {
        assertThat(economy.scale()).isEqualTo(2);
        assertThat(economy.startingBalance()).isEqualTo(money("100.00"));
        assertThat(economy.maxBalance()).isEqualTo(money("1000.00"));
        assertThat(economy.normalize(money("1.999"))).isEqualTo(money("1.99"));

        economy.updateSettings(new Economy.Settings(0, money("5.7"), money("10")));
        assertThat(economy.scale()).isZero();
        assertThat(economy.startingBalance()).isEqualTo(money("5"));
    }

    @Test
    void ensureAccountCreatesWithStartingBalanceAndLogsIt() {
        assertThat(economy.ensureAccount(A, "Alice")).isTrue();

        assertThat(economy.balance(A)).isEqualTo(money("100.00"));
        assertThat(changes).hasSize(1);
        Economy.Change change = changes.getFirst();
        assertThat(change.accounts()).containsExactly(new Account(A, "Alice", money("100.00")));
        assertThat(change.transaction()).isEqualTo(new Transaction(0, NOW, TransactionType.CREATE,
                null, null, A, "Alice", money("100.00"), Economy.SYSTEM));
    }

    @Test
    void ensureAccountForExistingSameNameDoesNothing() {
        economy.ensureAccount(A, "Alice");
        changes.clear();

        assertThat(economy.ensureAccount(A, "Alice")).isFalse();
        assertThat(changes).isEmpty();
    }

    @Test
    void ensureAccountUpdatesRenamedPlayerWithoutLogEntry() {
        economy.ensureAccount(A, "Alice");
        economy.deposit(A, money("5"), TransactionType.GIVE, "admin");
        changes.clear();

        assertThat(economy.ensureAccount(A, "Alicia")).isFalse();

        assertThat(economy.findByName("alice")).isEmpty();
        assertThat(economy.findByName("ALICIA")).contains(new Account(A, "Alicia", money("105.00")));
        assertThat(changes).containsExactly(new Economy.Change(List.of(new Account(A, "Alicia", money("105.00"))), null));
    }

    @Test
    void renameKeepsNameThatNowBelongsToSomeoneElse() {
        economy.ensureAccount(A, "Alice");
        economy.ensureAccount(B, "Alice"); // B took A's old name
        economy.ensureAccount(A, "Alicia");

        assertThat(economy.findByName("Alice").map(Account::uuid)).contains(B);
    }

    @Test
    void lookups() {
        economy.ensureAccount(A, "Alice");

        assertThat(economy.find(A)).isPresent();
        assertThat(economy.find(B)).isEmpty();
        assertThat(economy.findByName("nobody")).isEmpty();
        assertThat(economy.balance(B)).isEqualTo(money("0.00"));
    }

    @Test
    void depositRejectsBadInput() {
        twoAccounts();

        assertThat(economy.deposit(A, money("0"), TransactionType.GIVE, "x")).isEqualTo(EconomyResult.INVALID_AMOUNT);
        assertThat(economy.deposit(A, money("-1"), TransactionType.GIVE, "x")).isEqualTo(EconomyResult.INVALID_AMOUNT);
        assertThat(economy.deposit(A, money("0.009"), TransactionType.GIVE, "x")).isEqualTo(EconomyResult.INVALID_AMOUNT);
        assertThat(economy.deposit(C, money("1"), TransactionType.GIVE, "x")).isEqualTo(EconomyResult.NO_ACCOUNT);
        assertThat(economy.deposit(A, money("900.01"), TransactionType.GIVE, "x")).isEqualTo(EconomyResult.BALANCE_LIMIT);
        assertThat(changes).isEmpty();
        assertThat(economy.balance(A)).isEqualTo(money("100.00"));
    }

    @Test
    void depositAddsRoundedDownAmountAndLogs() {
        twoAccounts();

        assertThat(economy.deposit(A, money("900.009"), TransactionType.GIVE, "admin")).isEqualTo(EconomyResult.SUCCESS);

        assertThat(economy.balance(A)).isEqualTo(money("1000.00"));
        assertThat(changes.getFirst().transaction()).isEqualTo(new Transaction(0, NOW, TransactionType.GIVE,
                null, null, A, "Alice", money("900.00"), "admin"));
    }

    @Test
    void withdrawRejectsBadInput() {
        twoAccounts();

        assertThat(economy.withdraw(A, money("0"), TransactionType.TAKE, "x")).isEqualTo(EconomyResult.INVALID_AMOUNT);
        assertThat(economy.withdraw(C, money("1"), TransactionType.TAKE, "x")).isEqualTo(EconomyResult.NO_ACCOUNT);
        assertThat(economy.withdraw(A, money("100.01"), TransactionType.TAKE, "x")).isEqualTo(EconomyResult.INSUFFICIENT_FUNDS);
        assertThat(changes).isEmpty();
    }

    @Test
    void withdrawCanEmptyAccountAndLogsSender() {
        twoAccounts();

        assertThat(economy.withdraw(A, money("100"), TransactionType.VAULT_WITHDRAW, "Vault")).isEqualTo(EconomyResult.SUCCESS);

        assertThat(economy.balance(A)).isEqualTo(money("0.00"));
        assertThat(changes.getFirst().transaction()).isEqualTo(new Transaction(0, NOW, TransactionType.VAULT_WITHDRAW,
                A, "Alice", null, null, money("100.00"), "Vault"));
    }

    @Test
    void setRejectsBadInput() {
        twoAccounts();

        assertThat(economy.set(A, money("-0.01"), TransactionType.SET, "x")).isEqualTo(EconomyResult.INVALID_AMOUNT);
        assertThat(economy.set(A, money("1000.01"), TransactionType.SET, "x")).isEqualTo(EconomyResult.BALANCE_LIMIT);
        assertThat(economy.set(C, money("1"), TransactionType.SET, "x")).isEqualTo(EconomyResult.NO_ACCOUNT);
        assertThat(changes).isEmpty();
    }

    @Test
    void setReplacesBalanceAndLogsNewBalance() {
        twoAccounts();

        assertThat(economy.set(A, money("0"), TransactionType.RESET, "admin")).isEqualTo(EconomyResult.SUCCESS);

        assertThat(economy.balance(A)).isEqualTo(money("0.00"));
        assertThat(changes.getFirst().transaction().type()).isEqualTo(TransactionType.RESET);
        assertThat(changes.getFirst().transaction().amount()).isEqualTo(money("0.00"));
    }

    @Test
    void transferRejectsBadInput() {
        twoAccounts();
        economy.set(B, money("950"), TransactionType.SET, "x");
        changes.clear();

        assertThat(economy.transfer(A, A, money("1"), "x")).isEqualTo(EconomyResult.SAME_ACCOUNT);
        assertThat(economy.transfer(A, B, money("0.001"), "x")).isEqualTo(EconomyResult.INVALID_AMOUNT);
        assertThat(economy.transfer(C, B, money("1"), "x")).isEqualTo(EconomyResult.NO_ACCOUNT);
        assertThat(economy.transfer(A, C, money("1"), "x")).isEqualTo(EconomyResult.NO_ACCOUNT);
        assertThat(economy.transfer(A, B, money("100.01"), "x")).isEqualTo(EconomyResult.INSUFFICIENT_FUNDS);
        assertThat(economy.transfer(A, B, money("50.01"), "x")).isEqualTo(EconomyResult.BALANCE_LIMIT);
        assertThat(changes).isEmpty();
        assertThat(economy.balance(A)).isEqualTo(money("100.00"));
        assertThat(economy.balance(B)).isEqualTo(money("950.00"));
    }

    @Test
    void transferMovesMoneyAsOneChange() {
        twoAccounts();

        assertThat(economy.transfer(A, B, money("30.555"), "Alice")).isEqualTo(EconomyResult.SUCCESS);

        assertThat(economy.balance(A)).isEqualTo(money("69.45"));
        assertThat(economy.balance(B)).isEqualTo(money("130.55"));
        assertThat(changes).containsExactly(new Economy.Change(
                List.of(new Account(A, "Alice", money("69.45")), new Account(B, "Bob", money("130.55"))),
                new Transaction(0, NOW, TransactionType.PAY, A, "Alice", B, "Bob", money("30.55"), "Alice")));
    }

    @Test
    void rankedSortsByBalanceThenName() {
        economy.ensureAccount(A, "carol");
        economy.ensureAccount(B, "Bob");
        economy.ensureAccount(C, "Alice");
        economy.deposit(A, money("1"), TransactionType.GIVE, "x");

        assertThat(economy.ranked()).extracting(Account::name).containsExactly("carol", "Alice", "Bob");
    }

    @Test
    void loadAllReplacesAccountsWithoutNotifying() {
        economy.ensureAccount(C, "Old");
        changes.clear();

        economy.loadAll(List.of(new Account(A, "Alice", money("12.345")), new Account(B, "Bob", money("1"))));

        assertThat(changes).isEmpty();
        assertThat(economy.find(C)).isEmpty();
        assertThat(economy.findByName("old")).isEmpty();
        assertThat(economy.balance(A)).isEqualTo(money("12.34"));
        assertThat(economy.findByName("bob")).isPresent();
    }

    @Test
    void resultDescriptions() {
        assertThat(EconomyResult.INSUFFICIENT_FUNDS.description()).isEqualTo("Insufficient funds");
        assertThat(EconomyResult.SUCCESS.description()).isEqualTo("Success");
    }
}
