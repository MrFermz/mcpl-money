package dev.mcpl.money.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mcpl.money.MoneyConfig;
import dev.mcpl.money.TestSupport;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("deprecation")
class VaultEconomyTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    final MoneyConfig config = TestSupport.defaultConfig();
    final List<Economy.Change> changes = new ArrayList<>();
    final AtomicBoolean enabled = new AtomicBoolean(true);
    Economy economy;
    VaultEconomy vault;
    OfflinePlayer alice;
    OfflinePlayer stranger;

    @BeforeEach
    void setUp() {
        economy = new Economy(new Economy.Settings(2, new BigDecimal("100"), new BigDecimal("1000")),
                Clock.systemUTC(), changes::add);
        economy.ensureAccount(A, "Alice");
        changes.clear();
        vault = new VaultEconomy(economy, () -> config, enabled::get, "mcpl-money");
        alice = player(A, "Alice");
        stranger = player(B, "Bob");
    }

    private static OfflinePlayer player(UUID uuid, String name) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        return player;
    }

    private static void assertResponse(EconomyResponse response, ResponseType type, double amount, double balance, String error) {
        assertThat(response.type).isEqualTo(type);
        assertThat(Double.valueOf(response.amount)).isEqualTo(Double.valueOf(amount)); // boxed so NaN equals NaN
        assertThat(response.balance).isEqualTo(balance);
        assertThat(response.errorMessage).isEqualTo(error);
    }

    @Test
    void describesItself() {
        assertThat(vault.isEnabled()).isTrue();
        enabled.set(false);
        assertThat(vault.isEnabled()).isFalse();
        assertThat(vault.getName()).isEqualTo("mcpl-money");
        assertThat(vault.hasBankSupport()).isFalse();
        assertThat(vault.fractionalDigits()).isEqualTo(2);
        assertThat(vault.format(1234.5)).isEqualTo("$1,234.50");
        assertThat(vault.currencyNameSingular()).isEqualTo("dollar");
        assertThat(vault.currencyNamePlural()).isEqualTo("dollars");
    }

    @Test
    void accounts() {
        assertThat(vault.hasAccount("alice")).isTrue();
        assertThat(vault.hasAccount("bob")).isFalse();
        assertThat(vault.hasAccount(alice)).isTrue();
        assertThat(vault.hasAccount(stranger)).isFalse();
        assertThat(vault.hasAccount("Alice", "world")).isTrue();
        assertThat(vault.hasAccount(alice, "world")).isTrue();
    }

    @Test
    void createsAccountsOnlyFromPlayersWithName() {
        assertThat(vault.createPlayerAccount("Bob")).isFalse();
        assertThat(vault.createPlayerAccount("Bob", "world")).isFalse();
        assertThat(vault.createPlayerAccount(alice)).isFalse();
        assertThat(vault.createPlayerAccount(player(UUID.randomUUID(), null))).isFalse();

        assertThat(vault.createPlayerAccount(stranger, "world")).isTrue();
        assertThat(economy.balance(B)).isEqualByComparingTo("100");
    }

    @Test
    void balances() {
        assertThat(vault.getBalance("Alice")).isEqualTo(100.0);
        assertThat(vault.getBalance("Nobody")).isEqualTo(0.0);
        assertThat(vault.getBalance(alice)).isEqualTo(100.0);
        assertThat(vault.getBalance(stranger)).isEqualTo(0.0);
        assertThat(vault.getBalance("Alice", "world")).isEqualTo(100.0);
        assertThat(vault.getBalance(alice, "world")).isEqualTo(100.0);
    }

    @Test
    void has() {
        assertThat(vault.has("Alice", 100)).isTrue();
        assertThat(vault.has("Alice", 100.01)).isFalse();
        assertThat(vault.has("Nobody", 1)).isFalse();
        assertThat(vault.has("Alice", Double.NaN)).isFalse();
        assertThat(vault.has(alice, 50)).isTrue();
        assertThat(vault.has(stranger, 0)).isFalse();
        assertThat(vault.has("Alice", "world", 1)).isTrue();
        assertThat(vault.has(alice, "world", 1)).isTrue();
    }

    @Test
    void depositsAndLogsAsVault() {
        assertResponse(vault.depositPlayer(alice, 10.5), ResponseType.SUCCESS, 10.5, 110.5, null);
        assertResponse(vault.depositPlayer("Alice", 1), ResponseType.SUCCESS, 1, 111.5, null);
        assertResponse(vault.depositPlayer("Alice", "world", 1), ResponseType.SUCCESS, 1, 112.5, null);
        assertResponse(vault.depositPlayer(alice, "world", 1), ResponseType.SUCCESS, 1, 113.5, null);

        assertThat(changes).hasSize(4).allSatisfy(change -> {
            assertThat(change.transaction().type()).isEqualTo(TransactionType.VAULT_DEPOSIT);
            assertThat(change.transaction().actor()).isEqualTo("Vault");
        });
    }

    @Test
    void withdraws() {
        assertResponse(vault.withdrawPlayer(alice, 40), ResponseType.SUCCESS, 40, 60, null);
        assertResponse(vault.withdrawPlayer("Alice", 10), ResponseType.SUCCESS, 10, 50, null);
        assertResponse(vault.withdrawPlayer("Alice", "world", 10), ResponseType.SUCCESS, 10, 40, null);
        assertResponse(vault.withdrawPlayer(alice, "world", 10), ResponseType.SUCCESS, 10, 30, null);
        assertThat(changes.getFirst().transaction().type()).isEqualTo(TransactionType.VAULT_WITHDRAW);
    }

    @Test
    void failures() {
        assertResponse(vault.withdrawPlayer(alice, 100.01), ResponseType.FAILURE, 100.01, 100, "Insufficient funds");
        assertResponse(vault.depositPlayer(alice, 900.01), ResponseType.FAILURE, 900.01, 100, "Balance limit reached");
        assertResponse(vault.depositPlayer(alice, 0.001), ResponseType.FAILURE, 0.001, 100, "Invalid amount");
        assertResponse(vault.depositPlayer(alice, -1), ResponseType.FAILURE, -1, 100, "Invalid amount");
        assertResponse(vault.withdrawPlayer(alice, Double.NaN), ResponseType.FAILURE, Double.NaN, 100, "Invalid amount");
        assertResponse(vault.depositPlayer(alice, Double.POSITIVE_INFINITY), ResponseType.FAILURE,
                Double.POSITIVE_INFINITY, 100, "Invalid amount");
        assertResponse(vault.depositPlayer(stranger, 1), ResponseType.FAILURE, 1, 0, "No such account");
        assertResponse(vault.depositPlayer("Nobody", 1), ResponseType.FAILURE, 1, 0, "No such account");
        assertResponse(vault.withdrawPlayer("Nobody", 1), ResponseType.FAILURE, 1, 0, "No such account");
        assertThat(changes).isEmpty();
    }

    @Test
    void zeroAmountSucceedsWithoutLogEntry() {
        assertResponse(vault.depositPlayer(alice, 0), ResponseType.SUCCESS, 0, 100, null);
        assertResponse(vault.withdrawPlayer(alice, 0), ResponseType.SUCCESS, 0, 100, null);
        assertThat(changes).isEmpty();
    }

    @Test
    void banksAreNotSupported() {
        List<EconomyResponse> responses = List.of(
                vault.createBank("b", "Alice"), vault.createBank("b", alice), vault.deleteBank("b"),
                vault.bankBalance("b"), vault.bankHas("b", 1), vault.bankWithdraw("b", 1), vault.bankDeposit("b", 1),
                vault.isBankOwner("b", "Alice"), vault.isBankOwner("b", alice),
                vault.isBankMember("b", "Alice"), vault.isBankMember("b", alice));

        assertThat(responses).allSatisfy(r -> assertResponse(r, ResponseType.NOT_IMPLEMENTED, 0, 0,
                "mcpl-money does not support banks"));
        assertThat(vault.getBanks()).isEmpty();
    }

    @Test
    void hookRegistersProviderWithServicesManager() {
        ServicesManager services = mock(ServicesManager.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("mcpl-money");
        when(plugin.isEnabled()).thenReturn(true);

        VaultHook.register(services, plugin, economy, () -> config);

        ArgumentCaptor<net.milkbowl.vault.economy.Economy> provider =
                ArgumentCaptor.forClass(net.milkbowl.vault.economy.Economy.class);
        verify(services).register(org.mockito.ArgumentMatchers.eq(net.milkbowl.vault.economy.Economy.class),
                provider.capture(), org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.eq(ServicePriority.Normal));
        assertThat(provider.getValue().getName()).isEqualTo("mcpl-money");
        assertThat(provider.getValue().isEnabled()).isTrue();
    }
}
