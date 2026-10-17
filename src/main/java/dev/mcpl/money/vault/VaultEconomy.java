package dev.mcpl.money.vault;

import dev.mcpl.money.MoneyConfig;
import dev.mcpl.money.economy.Account;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.EconomyResult;
import dev.mcpl.money.economy.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * Exposes {@link Economy} to other plugins through the Vault economy API.
 *
 * <ul>
 *   <li>There is one balance per player; the {@code world} arguments are ignored.</li>
 *   <li>Banks are not supported.</li>
 *   <li>Name-based methods look the player up by the last name seen on this server. An account
 *       can't be created from a name alone, because accounts are keyed by UUID.</li>
 *   <li>Every deposit and withdrawal is written to the transaction log with actor {@code Vault}.</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // the name-based Vault methods are deprecated but still called by older plugins
public final class VaultEconomy implements net.milkbowl.vault.economy.Economy {

    static final String ACTOR = "Vault";
    private static final String NO_BANKS = "mcpl-money does not support banks";

    private final Economy economy;
    private final Supplier<MoneyConfig> config;
    private final BooleanSupplier enabled;
    private final String name;

    public VaultEconomy(Economy economy, Supplier<MoneyConfig> config, BooleanSupplier enabled, String name) {
        this.economy = economy;
        this.config = config;
        this.enabled = enabled;
        this.name = name;
    }

    @Override
    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return economy.scale();
    }

    @Override
    public String format(double amount) {
        return config.get().format(BigDecimal.valueOf(amount));
    }

    @Override
    public String currencyNamePlural() {
        return config.get().namePlural();
    }

    @Override
    public String currencyNameSingular() {
        return config.get().nameSingular();
    }

    // --- accounts ------------------------------------------------------------

    @Override
    public boolean hasAccount(String playerName) {
        return economy.findByName(playerName).isPresent();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return economy.find(player.getUniqueId()).isPresent();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return false;
    }

    /** @return true if a new account was created, false if it already existed or the player has no name */
    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        String playerName = player.getName();
        return playerName != null && economy.ensureAccount(player.getUniqueId(), playerName);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // --- balances ------------------------------------------------------------

    @Override
    public double getBalance(String playerName) {
        return economy.findByName(playerName).map(Account::balance).orElse(BigDecimal.ZERO).doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return economy.balance(player.getUniqueId()).doubleValue();
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(economy.findByName(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return has(economy.find(player.getUniqueId()), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    private static boolean has(Optional<Account> account, double amount) {
        return Double.isFinite(amount)
                && account.map(a -> a.balance().compareTo(BigDecimal.valueOf(amount)) >= 0).orElse(false);
    }

    // --- deposit / withdraw --------------------------------------------------

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return economy.findByName(playerName)
                .map(account -> change(account.uuid(), amount, TransactionType.VAULT_WITHDRAW))
                .orElseGet(() -> failure(amount, 0, EconomyResult.NO_ACCOUNT.description()));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return change(player.getUniqueId(), amount, TransactionType.VAULT_WITHDRAW);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return economy.findByName(playerName)
                .map(account -> change(account.uuid(), amount, TransactionType.VAULT_DEPOSIT))
                .orElseGet(() -> failure(amount, 0, EconomyResult.NO_ACCOUNT.description()));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return change(player.getUniqueId(), amount, TransactionType.VAULT_DEPOSIT);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    /**
     * Applies a Vault deposit or withdrawal. A zero amount succeeds without changing anything or
     * writing to the log; negative, NaN and infinite amounts fail.
     */
    private EconomyResponse change(UUID uuid, double amount, TransactionType type) {
        if (!Double.isFinite(amount) || amount < 0) {
            return failure(amount, economy.balance(uuid).doubleValue(), EconomyResult.INVALID_AMOUNT.description());
        }
        if (economy.find(uuid).isEmpty()) {
            return failure(amount, 0, EconomyResult.NO_ACCOUNT.description());
        }
        if (amount == 0) {
            return new EconomyResponse(0, economy.balance(uuid).doubleValue(), ResponseType.SUCCESS, null);
        }
        BigDecimal value = BigDecimal.valueOf(amount);
        EconomyResult result = type == TransactionType.VAULT_DEPOSIT
                ? economy.deposit(uuid, value, type, ACTOR)
                : economy.withdraw(uuid, value, type, ACTOR);
        double balance = economy.balance(uuid).doubleValue();
        if (result != EconomyResult.SUCCESS) {
            return failure(amount, balance, result.description());
        }
        return new EconomyResponse(amount, balance, ResponseType.SUCCESS, null);
    }

    private static EconomyResponse failure(double amount, double balance, String message) {
        return new EconomyResponse(amount, balance, ResponseType.FAILURE, message);
    }

    // --- banks (not supported) -----------------------------------------------

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, NO_BANKS);
    }

    @Override
    public EconomyResponse createBank(String bankName, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String bankName, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String bankName) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String bankName) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String bankName, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String bankName, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String bankName, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String bankName, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String bankName, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String bankName, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String bankName, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
