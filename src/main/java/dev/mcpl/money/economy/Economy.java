package dev.mcpl.money.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * All accounts, held in memory. This is the source of truth while the server runs: every
 * successful change is applied here first and then handed to the {@code listener} as a
 * {@link Change}, which the plugin writes to the database in the background.
 *
 * <p>Balances are kept at a fixed scale (the configured number of decimal places) so rounding
 * happens once, when an amount comes in, and never accumulates.
 *
 * <p>Methods are synchronized because Vault may call in from any thread.
 */
public final class Economy {

    /** System actor name used in the transaction log for automatic changes. */
    public static final String SYSTEM = "SYSTEM";

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, UUID> uuidByName = new HashMap<>();
    private final Clock clock;
    private final Consumer<Change> listener;
    private Settings settings;

    /**
     * @param scale           decimal places kept on every balance
     * @param startingBalance balance given to new accounts
     * @param maxBalance      no balance may go above this
     */
    public record Settings(int scale, BigDecimal startingBalance, BigDecimal maxBalance) {
    }

    /**
     * Accounts whose stored state changed, plus the log entry describing why. The transaction is
     * null when only an account's name changed.
     */
    public record Change(List<Account> accounts, Transaction transaction) {
    }

    public Economy(Settings settings, Clock clock, Consumer<Change> listener) {
        this.settings = settings;
        this.clock = clock;
        this.listener = listener;
    }

    public synchronized void updateSettings(Settings settings) {
        this.settings = settings;
    }

    public synchronized int scale() {
        return settings.scale();
    }

    public synchronized BigDecimal maxBalance() {
        return normalize(settings.maxBalance());
    }

    public synchronized BigDecimal startingBalance() {
        return normalize(settings.startingBalance());
    }

    /** Rounds an amount down to the configured number of decimal places. */
    public synchronized BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(settings.scale(), RoundingMode.DOWN);
    }

    /** Replaces every account with the ones loaded from storage. Does not notify the listener. */
    public synchronized void loadAll(Collection<Account> loaded) {
        accounts.clear();
        uuidByName.clear();
        for (Account account : loaded) {
            put(account.withBalance(normalize(account.balance())));
        }
    }

    /**
     * Creates the account with the starting balance if it is missing, and keeps the stored name
     * up to date.
     *
     * @return true if a new account was created
     */
    public synchronized boolean ensureAccount(UUID uuid, String name) {
        Account existing = accounts.get(uuid);
        if (existing == null) {
            Account created = new Account(uuid, name, startingBalance());
            put(created);
            emit(List.of(created), log(TransactionType.CREATE, null, created, created.balance(), SYSTEM));
            return true;
        }
        if (!existing.name().equals(name)) {
            uuidByName.remove(existing.name().toLowerCase(Locale.ROOT), uuid);
            Account renamed = new Account(uuid, name, existing.balance());
            put(renamed);
            emit(List.of(renamed), null);
        }
        return false;
    }

    public synchronized Optional<Account> find(UUID uuid) {
        return Optional.ofNullable(accounts.get(uuid));
    }

    /** Case-insensitive lookup by the most recent name seen for an account. */
    public synchronized Optional<Account> findByName(String name) {
        UUID uuid = uuidByName.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? Optional.empty() : find(uuid);
    }

    /** @return the balance, or zero if there is no such account */
    public synchronized BigDecimal balance(UUID uuid) {
        Account account = accounts.get(uuid);
        return account == null ? normalize(BigDecimal.ZERO) : account.balance();
    }

    public synchronized EconomyResult deposit(UUID uuid, BigDecimal amount, TransactionType type, String actor) {
        BigDecimal value = normalize(amount);
        if (value.signum() <= 0) {
            return EconomyResult.INVALID_AMOUNT;
        }
        Account account = accounts.get(uuid);
        if (account == null) {
            return EconomyResult.NO_ACCOUNT;
        }
        BigDecimal next = account.balance().add(value);
        if (next.compareTo(maxBalance()) > 0) {
            return EconomyResult.BALANCE_LIMIT;
        }
        Account updated = account.withBalance(next);
        put(updated);
        emit(List.of(updated), log(type, null, updated, value, actor));
        return EconomyResult.SUCCESS;
    }

    public synchronized EconomyResult withdraw(UUID uuid, BigDecimal amount, TransactionType type, String actor) {
        BigDecimal value = normalize(amount);
        if (value.signum() <= 0) {
            return EconomyResult.INVALID_AMOUNT;
        }
        Account account = accounts.get(uuid);
        if (account == null) {
            return EconomyResult.NO_ACCOUNT;
        }
        BigDecimal next = account.balance().subtract(value);
        if (next.signum() < 0) {
            return EconomyResult.INSUFFICIENT_FUNDS;
        }
        Account updated = account.withBalance(next);
        put(updated);
        emit(List.of(updated), log(type, updated, null, value, actor));
        return EconomyResult.SUCCESS;
    }

    /** Sets a balance outright. The logged amount is the new balance. */
    public synchronized EconomyResult set(UUID uuid, BigDecimal amount, TransactionType type, String actor) {
        BigDecimal value = normalize(amount);
        if (value.signum() < 0) {
            return EconomyResult.INVALID_AMOUNT;
        }
        if (value.compareTo(maxBalance()) > 0) {
            return EconomyResult.BALANCE_LIMIT;
        }
        Account account = accounts.get(uuid);
        if (account == null) {
            return EconomyResult.NO_ACCOUNT;
        }
        Account updated = account.withBalance(value);
        put(updated);
        emit(List.of(updated), log(type, null, updated, value, actor));
        return EconomyResult.SUCCESS;
    }

    /** Moves money between two accounts as one change: both balances update, or neither does. */
    public synchronized EconomyResult transfer(UUID from, UUID to, BigDecimal amount, String actor) {
        if (from.equals(to)) {
            return EconomyResult.SAME_ACCOUNT;
        }
        BigDecimal value = normalize(amount);
        if (value.signum() <= 0) {
            return EconomyResult.INVALID_AMOUNT;
        }
        Account sender = accounts.get(from);
        Account receiver = accounts.get(to);
        if (sender == null || receiver == null) {
            return EconomyResult.NO_ACCOUNT;
        }
        BigDecimal senderNext = sender.balance().subtract(value);
        if (senderNext.signum() < 0) {
            return EconomyResult.INSUFFICIENT_FUNDS;
        }
        BigDecimal receiverNext = receiver.balance().add(value);
        if (receiverNext.compareTo(maxBalance()) > 0) {
            return EconomyResult.BALANCE_LIMIT;
        }
        Account updatedSender = sender.withBalance(senderNext);
        Account updatedReceiver = receiver.withBalance(receiverNext);
        put(updatedSender);
        put(updatedReceiver);
        emit(List.of(updatedSender, updatedReceiver),
                log(TransactionType.PAY, updatedSender, updatedReceiver, value, actor));
        return EconomyResult.SUCCESS;
    }

    /** Accounts sorted from richest to poorest, ties broken by name. */
    public synchronized List<Account> ranked() {
        List<Account> list = new ArrayList<>(accounts.values());
        list.sort(Comparator.comparing(Account::balance).reversed()
                .thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void put(Account account) {
        accounts.put(account.uuid(), account);
        uuidByName.put(account.name().toLowerCase(Locale.ROOT), account.uuid());
    }

    private Transaction log(TransactionType type, Account from, Account to, BigDecimal amount, String actor) {
        return new Transaction(0, clock.instant(), type,
                from == null ? null : from.uuid(), from == null ? null : from.name(),
                to == null ? null : to.uuid(), to == null ? null : to.name(),
                amount, actor);
    }

    private void emit(List<Account> changed, Transaction transaction) {
        listener.accept(new Change(changed, transaction));
    }
}
