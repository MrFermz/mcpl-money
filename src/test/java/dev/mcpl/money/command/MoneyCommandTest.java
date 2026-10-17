package dev.mcpl.money.command;

import static dev.mcpl.money.TestSupport.messages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.mcpl.money.MoneyConfig;
import dev.mcpl.money.TestSupport;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.Transaction;
import dev.mcpl.money.economy.TransactionType;
import dev.mcpl.money.storage.Database;
import dev.mcpl.money.storage.HistoryPage;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MoneyCommandTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final Set<String> PLAYER_PERMS = Set.of(MoneyCommand.PERM_BALANCE, MoneyCommand.PERM_BALANCE_OTHERS,
            MoneyCommand.PERM_PAY, MoneyCommand.PERM_TOP, MoneyCommand.PERM_HISTORY);

    final Server server = mock(Server.class);
    final Database database = mock(Database.class);
    final Runnable reload = mock(Runnable.class);
    final Logger logger = mock(Logger.class);
    final AtomicReference<MoneyConfig> config = new AtomicReference<>(TestSupport.defaultConfig());
    final List<Player> online = new ArrayList<>();
    final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    Economy economy;
    Player alice;
    Player bob;
    ConsoleCommandSender console;

    @BeforeEach
    void setUp() {
        economy = new Economy(config.get().economySettings(), Clock.systemUTC(), change -> { });
        economy.ensureAccount(A, "Alice");
        economy.ensureAccount(B, "Bob");
        alice = player(A, "Alice", PLAYER_PERMS);
        bob = player(B, "Bob", PLAYER_PERMS);
        console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(console.hasPermission(anyString())).thenReturn(true);
        online.add(alice);
        online.add(bob);
        when(server.getOnlinePlayers()).thenAnswer(inv -> (Collection<? extends Player>) List.copyOf(online));
        when(server.getPlayer(B)).thenReturn(bob);
        dispatcher.getRoot().addChild(
                new MoneyCommand(economy, config::get, server, database, reload, logger).build());
    }

    private static Player player(UUID uuid, String name, Set<String> permissions) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.hasPermission(anyString())).thenAnswer(inv -> permissions.contains(inv.<String>getArgument(0)));
        return player;
    }

    private static CommandSourceStack source(CommandSender sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    private int run(CommandSender sender, String command) throws CommandSyntaxException {
        return dispatcher.execute(command, source(sender));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private void useConfig(java.util.function.Consumer<YamlConfiguration> change) {
        YamlConfiguration yaml = TestSupport.defaultYaml();
        change.accept(yaml);
        config.set(TestSupport.config(yaml));
    }

    // --- balance -------------------------------------------------------------

    @Test
    void moneyShowsOwnBalance() throws Exception {
        assertThat(run(alice, "money")).isEqualTo(1);
        assertThat(run(alice, "money balance")).isEqualTo(1);

        assertThat(messages(alice)).containsExactly("[Money] Your balance: $100.00", "[Money] Your balance: $100.00");
    }

    @Test
    void moneyWithoutPermission() throws Exception {
        Player guest = player(UUID.randomUUID(), "Guest", Set.of());

        assertThat(run(guest, "money")).isZero();
        assertThat(messages(guest)).containsExactly("[Money] You don't have permission to do that.");
        assertThatThrownBy(() -> run(guest, "money balance")).isInstanceOf(CommandSyntaxException.class);
    }

    @Test
    void consoleHasNoOwnBalance() throws Exception {
        assertThat(run(console, "money")).isZero();
        assertThat(messages(console)).containsExactly("[Money] Only players can use this command.");
    }

    @Test
    void balanceOfAnotherPlayer() throws Exception {
        assertThat(run(alice, "money balance bob")).isEqualTo(1);
        assertThat(run(alice, "money balance Nobody")).isZero();

        assertThat(messages(alice)).containsExactly(
                "[Money] Bob's balance: $100.00",
                "[Money] No account found for Nobody.");
        Player limited = player(UUID.randomUUID(), "Limited", Set.of(MoneyCommand.PERM_BALANCE));
        assertThatThrownBy(() -> run(limited, "money balance Bob")).isInstanceOf(CommandSyntaxException.class);
    }

    @Test
    void suggestsOnlinePlayerNames() {
        var parse = dispatcher.parse("money pay b", source(alice));

        assertThat(dispatcher.getCompletionSuggestions(parse).join().getList())
                .extracting(Suggestion::getText).containsExactly("Bob");
    }

    // --- pay -----------------------------------------------------------------

    @Test
    void payMovesMoneyAndNotifiesOnlineRecipient() throws Exception {
        assertThat(run(alice, "money pay bob 25.555")).isEqualTo(1);

        assertThat(economy.balance(A)).isEqualByComparingTo("74.45");
        assertThat(economy.balance(B)).isEqualByComparingTo("125.55");
        assertThat(messages(alice)).containsExactly("[Money] You sent $25.55 to Bob.");
        assertThat(messages(bob)).containsExactly("[Money] You received $25.55 from Alice.");
    }

    @Test
    void payToOfflinePlayerOnlyTellsSender() throws Exception {
        assertThat(run(bob, "money pay Alice 1")).isEqualTo(1);

        assertThat(messages(bob)).containsExactly("[Money] You sent $1.00 to Alice.");
        assertThat(messages(alice)).isEmpty();
    }

    @Test
    void payCreatesSenderAccountIfMissing() throws Exception {
        Player newcomer = player(UUID.randomUUID(), "Newcomer", PLAYER_PERMS);

        assertThat(run(newcomer, "money pay Bob 5")).isEqualTo(1);
        assertThat(economy.balance(newcomer.getUniqueId())).isEqualByComparingTo("95");
    }

    @Test
    void payRejections() throws Exception {
        assertThat(run(console, "money pay Bob 1")).isZero();
        assertThat(run(alice, "money pay Nobody 1")).isZero();
        assertThat(run(alice, "money pay Bob abc")).isZero();
        assertThat(run(alice, "money pay alice 1")).isZero();
        assertThat(run(alice, "money pay Bob 500")).isZero();
        assertThat(run(alice, "money pay Bob 0")).isZero();
        economy.set(B, money("1000000000000"), TransactionType.SET, "test");
        assertThat(run(alice, "money pay Bob 1")).isZero();

        assertThat(messages(console)).containsExactly("[Money] Only players can use this command.");
        assertThat(messages(alice)).containsExactly(
                "[Money] No account found for Nobody.",
                "[Money] abc is not a valid amount.",
                "[Money] You cannot pay yourself.",
                "[Money] You don't have enough money. Your balance: $100.00",
                "[Money] 0 is not a valid amount.",
                "[Money] That would put Bob above the maximum balance of $1,000,000,000,000.00.");
        assertThat(economy.balance(A)).isEqualByComparingTo("100");
    }

    // --- top -----------------------------------------------------------------

    @Test
    void topListsRichestFirstWithPages() throws Exception {
        useConfig(yaml -> yaml.set("top-page-size", 1));
        economy.deposit(B, money("50"), TransactionType.GIVE, "test");

        assertThat(run(alice, "money top")).isEqualTo(1);
        assertThat(run(alice, "money top 2")).isEqualTo(1);
        assertThat(run(alice, "money top 9")).isEqualTo(1);

        assertThat(messages(alice)).containsExactly(
                "----- Richest players (page 1/2) -----", "1. Bob - $150.00",
                "----- Richest players (page 2/2) -----", "2. Alice - $100.00",
                "----- Richest players (page 2/2) -----", "2. Alice - $100.00");
    }

    @Test
    void topWithNoAccounts() throws Exception {
        economy.loadAll(List.of());

        assertThat(run(alice, "money top")).isZero();
        assertThat(messages(alice)).containsExactly("[Money] No accounts yet.");
    }

    // --- history -------------------------------------------------------------

    private static Transaction tx(TransactionType type, UUID from, String fromName, UUID to, String toName, String amount, String actor) {
        return new Transaction(1, Instant.parse("2026-02-03T04:05:00Z"), type, from, fromName, to, toName,
                new BigDecimal(amount), actor);
    }

    @Test
    void historyShowsOwnTransactionsWithDirection() throws Exception {
        List<Transaction> rows = List.of(
                tx(TransactionType.PAY, A, "Alice", B, "Bob", "5", "Alice"),
                tx(TransactionType.PAY, B, "Bob", A, "Alice", "2", "Bob"),
                tx(TransactionType.SET, null, null, A, "Alice", "50", "CONSOLE"),
                tx(TransactionType.RESET, null, null, A, "Alice", "100", "CONSOLE"),
                tx(TransactionType.TAKE, A, "Alice", null, null, "1", "CONSOLE"));
        when(database.history(A, 2, 10)).thenReturn(CompletableFuture.completedFuture(new HistoryPage(2, 3, 25, rows)));

        assertThat(run(alice, "money history 2")).isEqualTo(1);

        assertThat(messages(alice)).containsExactly(
                "----- Transactions for Alice (page 2/3) -----",
                "2026-02-03 04:05 pay -$5.00 (Alice -> Bob, by Alice)",
                "2026-02-03 04:05 pay +$2.00 (Bob -> Alice, by Bob)",
                "2026-02-03 04:05 set =$50.00 (- -> Alice, by CONSOLE)",
                "2026-02-03 04:05 reset =$100.00 (- -> Alice, by CONSOLE)",
                "2026-02-03 04:05 take -$1.00 (Alice -> -, by CONSOLE)");
    }

    @Test
    void historyEmptyAndErrors() throws Exception {
        when(database.history(A, 1, 10)).thenReturn(CompletableFuture.completedFuture(new HistoryPage(1, 1, 0, List.of())));
        when(database.history(B, 1, 10)).thenReturn(CompletableFuture.failedFuture(new SQLException("down")));

        assertThat(run(alice, "money history")).isEqualTo(1);
        assertThat(run(bob, "money history")).isEqualTo(1);
        assertThat(run(console, "money history")).isZero();

        assertThat(messages(alice)).containsExactly("[Money] No transactions for Alice.");
        assertThat(messages(bob)).containsExactly("[Money] Could not load transactions. See the server log.");
        assertThat(messages(console)).containsExactly("[Money] Only players can use this command.");
        verify(logger).log(org.mockito.ArgumentMatchers.eq(java.util.logging.Level.WARNING),
                org.mockito.ArgumentMatchers.contains("Bob"), org.mockito.ArgumentMatchers.any(SQLException.class));
    }

    @Test
    void historyOfAnotherPlayer() throws Exception {
        when(database.history(B, 1, 10)).thenReturn(CompletableFuture.completedFuture(new HistoryPage(1, 1, 0, List.of())));
        when(database.history(B, 3, 10)).thenReturn(CompletableFuture.completedFuture(new HistoryPage(1, 1, 0, List.of())));

        assertThat(run(console, "money history Bob")).isEqualTo(1);
        assertThat(run(console, "money history Bob 3")).isEqualTo(1);
        assertThat(run(console, "money history Nobody")).isZero();

        verify(database).history(B, 3, 10);
        assertThat(messages(console)).containsExactly(
                "[Money] No transactions for Bob.",
                "[Money] No transactions for Bob.",
                "[Money] No account found for Nobody.");
    }

    @Test
    void historyOfAnotherPlayerNeedsPermission() {
        assertThatThrownBy(() -> run(alice, "money history Bob")).isInstanceOf(CommandSyntaxException.class);
    }

    @Test
    void numberIsPageAndQuotedDigitsAreAPlayerName() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID digitsId = UUID.randomUUID();
        Player admin = player(adminId, "Admin", Set.of(MoneyCommand.PERM_HISTORY, MoneyCommand.PERM_HISTORY_OTHERS));
        economy.ensureAccount(digitsId, "123");
        HistoryPage empty = new HistoryPage(1, 1, 0, List.of());
        when(database.history(adminId, 2, 10)).thenReturn(CompletableFuture.completedFuture(empty));
        when(database.history(digitsId, 1, 10)).thenReturn(CompletableFuture.completedFuture(empty));

        assertThat(run(admin, "money history 2")).isEqualTo(1);
        assertThat(run(admin, "money history \"123\"")).isEqualTo(1);

        verify(database).history(adminId, 2, 10);
        verify(database).history(digitsId, 1, 10);
        assertThat(messages(admin)).containsExactly(
                "[Money] No transactions for Admin.",
                "[Money] No transactions for 123.");
    }

    // --- admin ---------------------------------------------------------------

    @Test
    void adminCommandsNeedPermission() {
        assertThatThrownBy(() -> run(alice, "money give Bob 1")).isInstanceOf(CommandSyntaxException.class);
        assertThatThrownBy(() -> run(alice, "money reload")).isInstanceOf(CommandSyntaxException.class);
    }

    @Test
    void give() throws Exception {
        assertThat(run(console, "money give Bob 10.999")).isEqualTo(1);
        assertThat(run(console, "money give Nobody 1")).isZero();
        assertThat(run(console, "money give Bob x")).isZero();
        assertThat(run(console, "money give Bob 1000000000000")).isZero();

        assertThat(messages(console)).containsExactly(
                "[Money] Gave $10.99 to Bob. New balance: $110.99",
                "[Money] No account found for Nobody.",
                "[Money] x is not a valid amount.",
                "[Money] That would put Bob above the maximum balance of $1,000,000,000,000.00.");
    }

    @Test
    void take() throws Exception {
        assertThat(run(console, "money take Bob 30")).isEqualTo(1);
        assertThat(run(console, "money take Bob 71")).isZero();
        assertThat(run(console, "money take Bob 0")).isZero();
        assertThat(run(console, "money take Nobody 1")).isZero();
        assertThat(run(console, "money take Bob x")).isZero();

        assertThat(messages(console)).containsExactly(
                "[Money] Took $30.00 from Bob. New balance: $70.00",
                "[Money] Bob only has $70.00.",
                "[Money] 0 is not a valid amount.",
                "[Money] No account found for Nobody.",
                "[Money] x is not a valid amount.");
    }

    @Test
    void set() throws Exception {
        assertThat(run(console, "money set Bob 5")).isEqualTo(1);
        assertThat(run(console, "money set Bob -5")).isZero();
        assertThat(run(console, "money set Nobody 1")).isZero();
        assertThat(run(console, "money set Bob x")).isZero();

        assertThat(economy.balance(B)).isEqualByComparingTo("5");
        assertThat(messages(console)).containsExactly(
                "[Money] Set Bob's balance to $5.00.",
                "[Money] -5 is not a valid amount.",
                "[Money] No account found for Nobody.",
                "[Money] x is not a valid amount.");
    }

    @Test
    void reset() throws Exception {
        economy.set(B, money("7"), TransactionType.SET, "test");

        assertThat(run(console, "money reset Bob")).isEqualTo(1);
        assertThat(run(console, "money reset Nobody")).isZero();
        economy.updateSettings(new Economy.Settings(2, money("5000"), money("1000")));
        assertThat(run(console, "money reset Bob")).isZero();

        assertThat(messages(console)).containsExactly(
                "[Money] Reset Bob's balance to $100.00.",
                "[Money] No account found for Nobody.",
                "[Money] That would put Bob above the maximum balance of $1,000.00.");
    }

    @Test
    void reloadReportsSuccessAndFailure() throws Exception {
        assertThat(run(console, "money reload")).isEqualTo(1);
        verify(reload).run();
        doThrow(new IllegalArgumentException("Unknown storage.type 'x'")).when(reload).run();
        assertThat(run(console, "money reload")).isZero();

        assertThat(messages(console)).containsExactly(
                "[Money] Configuration reloaded.",
                "[Money] Reload failed, keeping the old configuration: Unknown storage.type 'x'");
    }

    @Test
    void signHelper() {
        assertThat(MoneyCommand.sign(tx(TransactionType.GIVE, null, null, A, "Alice", "1", "x"), A)).isEqualTo("+");
        verify(server, never()).getPlayer(A);
    }
}
