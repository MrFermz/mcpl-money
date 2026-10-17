package dev.mcpl.money.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.mcpl.money.MoneyConfig;
import dev.mcpl.money.economy.Account;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.EconomyResult;
import dev.mcpl.money.economy.Transaction;
import dev.mcpl.money.economy.TransactionType;
import dev.mcpl.money.storage.Database;
import dev.mcpl.money.storage.HistoryPage;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /money} command and all of its subcommands:
 *
 * <pre>
 * /money                          your balance
 * /money balance [player]         your or another player's balance
 * /money pay &lt;player&gt; &lt;amount&gt;
 * /money top [page]
 * /money history [page]           your transactions
 * /money history &lt;player&gt; [page]  another player's transactions (a number is read as a page;
 *                                 quote names that are only digits: "123")
 * /money give|take|set &lt;player&gt; &lt;amount&gt;
 * /money reset &lt;player&gt;
 * /money reload
 * </pre>
 */
public final class MoneyCommand {

    public static final String PERM_BALANCE = "money.balance";
    public static final String PERM_BALANCE_OTHERS = "money.balance.others";
    public static final String PERM_PAY = "money.pay";
    public static final String PERM_TOP = "money.top";
    public static final String PERM_HISTORY = "money.history";
    public static final String PERM_HISTORY_OTHERS = "money.history.others";
    public static final String PERM_ADMIN = "money.admin";

    private static final String PLAYER = "player";
    private static final String AMOUNT = "amount";
    private static final String PAGE = "page";

    private final Economy economy;
    private final Supplier<MoneyConfig> config;
    private final Server server;
    private final Database database;
    private final Runnable reload;
    private final Logger logger;

    public MoneyCommand(Economy economy, Supplier<MoneyConfig> config, Server server, Database database,
                        Runnable reload, Logger logger) {
        this.economy = economy;
        this.config = config;
        this.server = server;
        this.database = database;
        this.reload = reload;
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("money")
                .executes(ctx -> balanceSelf(ctx.getSource().getSender()))
                .then(Commands.literal("balance")
                        .requires(permission(PERM_BALANCE))
                        .executes(ctx -> balanceSelf(ctx.getSource().getSender()))
                        .then(player()
                                .requires(permission(PERM_BALANCE_OTHERS))
                                .executes(this::balanceOther)))
                .then(Commands.literal("pay")
                        .requires(permission(PERM_PAY))
                        .then(player().then(amount().executes(this::pay))))
                .then(Commands.literal("top")
                        .requires(permission(PERM_TOP))
                        .executes(ctx -> top(ctx.getSource().getSender(), 1))
                        .then(page().executes(ctx -> top(ctx.getSource().getSender(), page(ctx)))))
                .then(Commands.literal("history")
                        .requires(permission(PERM_HISTORY))
                        .executes(ctx -> historySelf(ctx.getSource().getSender(), 1))
                        // The page node must come first: Brigadier picks the first child that parses, so
                        // "history 2" is page 2. Players whose name is only digits are reached quoted.
                        .then(page().executes(ctx -> historySelf(ctx.getSource().getSender(), page(ctx))))
                        .then(player()
                                .requires(permission(PERM_HISTORY_OTHERS))
                                .executes(ctx -> historyOther(ctx, 1))
                                .then(page().executes(ctx -> historyOther(ctx, page(ctx))))))
                .then(Commands.literal("give")
                        .requires(permission(PERM_ADMIN))
                        .then(player().then(amount().executes(this::give))))
                .then(Commands.literal("take")
                        .requires(permission(PERM_ADMIN))
                        .then(player().then(amount().executes(this::take))))
                .then(Commands.literal("set")
                        .requires(permission(PERM_ADMIN))
                        .then(player().then(amount().executes(this::set))))
                .then(Commands.literal("reset")
                        .requires(permission(PERM_ADMIN))
                        .then(player().executes(this::reset)))
                .then(Commands.literal("reload")
                        .requires(permission(PERM_ADMIN))
                        .executes(ctx -> reload(ctx.getSource().getSender())))
                .build();
    }

    // --- arguments -----------------------------------------------------------

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }

    /**
     * Player name, suggesting online players. Offline players with an account also work. Accepts
     * quotes ({@code "123"}) so names made only of digits can be told apart from page numbers.
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument(PLAYER, StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String typed = builder.getRemainingLowerCase();
                    for (Player online : server.getOnlinePlayers()) {
                        if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                            builder.suggest(online.getName());
                        }
                    }
                    return builder.buildFuture();
                });
    }

    /** Parsed as BigDecimal (not double) so large or precise amounts are exact. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> amount() {
        return Commands.argument(AMOUNT, StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> page() {
        return Commands.argument(PAGE, IntegerArgumentType.integer(1));
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, PAGE);
    }

    /** Looks up the account named by the player argument, telling the sender if there is none. */
    private Optional<Account> target(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, PLAYER);
        Optional<Account> account = economy.findByName(name);
        if (account.isEmpty()) {
            ctx.getSource().getSender().sendMessage(
                    config.get().message("player-not-found", Placeholder.unparsed(PLAYER, name)));
        }
        return account;
    }

    /** @return the amount, or null (after telling the sender) if it is not a number */
    private BigDecimal amount(CommandContext<CommandSourceStack> ctx) {
        String input = StringArgumentType.getString(ctx, AMOUNT);
        try {
            return new BigDecimal(input);
        } catch (NumberFormatException e) {
            invalidAmount(ctx.getSource().getSender(), input);
            return null;
        }
    }

    private void invalidAmount(CommandSender sender, String input) {
        sender.sendMessage(config.get().message("invalid-amount", Placeholder.unparsed("input", input)));
    }

    /** Reports a failed change that isn't specific to one command. */
    private int fail(CommandSender sender, EconomyResult result, Account target, String input) {
        if (result == EconomyResult.BALANCE_LIMIT) {
            MoneyConfig c = config.get();
            sender.sendMessage(c.message("balance-limit",
                    Placeholder.unparsed(PLAYER, target.name()),
                    Placeholder.unparsed("max", c.format(economy.maxBalance()))));
        } else {
            invalidAmount(sender, input);
        }
        return 0;
    }

    // --- balance -------------------------------------------------------------

    private int balanceSelf(CommandSender sender) {
        MoneyConfig c = config.get();
        if (!sender.hasPermission(PERM_BALANCE)) {
            sender.sendMessage(c.message("no-permission"));
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(c.message("player-only"));
            return 0;
        }
        String balance = c.format(economy.balance(player.getUniqueId()));
        sender.sendMessage(c.message("balance-self", Placeholder.unparsed("balance", balance)));
        return Command.SINGLE_SUCCESS;
    }

    private int balanceOther(CommandContext<CommandSourceStack> ctx) {
        Optional<Account> target = target(ctx);
        if (target.isEmpty()) {
            return 0;
        }
        MoneyConfig c = config.get();
        ctx.getSource().getSender().sendMessage(c.message("balance-other",
                Placeholder.unparsed(PLAYER, target.get().name()),
                Placeholder.unparsed("balance", c.format(target.get().balance()))));
        return Command.SINGLE_SUCCESS;
    }

    // --- pay -----------------------------------------------------------------

    private int pay(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MoneyConfig c = config.get();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(c.message("player-only"));
            return 0;
        }
        Optional<Account> target = target(ctx);
        if (target.isEmpty()) {
            return 0;
        }
        BigDecimal amount = amount(ctx);
        if (amount == null) {
            return 0;
        }
        Account to = target.get();
        if (to.uuid().equals(player.getUniqueId())) {
            sender.sendMessage(c.message("pay-self"));
            return 0;
        }

        economy.ensureAccount(player.getUniqueId(), player.getName());
        EconomyResult result = economy.transfer(player.getUniqueId(), to.uuid(), amount, player.getName());
        if (result == EconomyResult.INSUFFICIENT_FUNDS) {
            sender.sendMessage(c.message("pay-insufficient",
                    Placeholder.unparsed("balance", c.format(economy.balance(player.getUniqueId())))));
            return 0;
        }
        if (result != EconomyResult.SUCCESS) {
            return fail(sender, result, to, StringArgumentType.getString(ctx, AMOUNT));
        }

        String formatted = c.format(economy.normalize(amount));
        sender.sendMessage(c.message("pay-sent",
                Placeholder.unparsed("amount", formatted),
                Placeholder.unparsed(PLAYER, to.name())));
        Player recipient = server.getPlayer(to.uuid());
        if (recipient != null) {
            recipient.sendMessage(c.message("pay-received",
                    Placeholder.unparsed("amount", formatted),
                    Placeholder.unparsed(PLAYER, player.getName())));
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- top -----------------------------------------------------------------

    private int top(CommandSender sender, int requestedPage) {
        MoneyConfig c = config.get();
        List<Account> ranked = economy.ranked();
        if (ranked.isEmpty()) {
            sender.sendMessage(c.message("top-empty"));
            return 0;
        }
        int size = c.topPageSize();
        int pages = (ranked.size() + size - 1) / size;
        int page = Math.min(requestedPage, pages);
        int from = (page - 1) * size;
        int to = Math.min(from + size, ranked.size());

        sender.sendMessage(c.message("top-header",
                Placeholder.unparsed(PAGE, String.valueOf(page)),
                Placeholder.unparsed("pages", String.valueOf(pages))));
        for (int i = from; i < to; i++) {
            Account account = ranked.get(i);
            sender.sendMessage(c.message("top-entry",
                    Placeholder.unparsed("rank", String.valueOf(i + 1)),
                    Placeholder.unparsed(PLAYER, account.name()),
                    Placeholder.unparsed("balance", c.format(account.balance()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- history -------------------------------------------------------------

    private int historySelf(CommandSender sender, int page) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.get().message("player-only"));
            return 0;
        }
        return history(sender, player.getUniqueId(), player.getName(), page);
    }

    private int historyOther(CommandContext<CommandSourceStack> ctx, int page) {
        Optional<Account> target = target(ctx);
        if (target.isEmpty()) {
            return 0;
        }
        return history(ctx.getSource().getSender(), target.get().uuid(), target.get().name(), page);
    }

    /** Loads the page on the database thread and sends it when ready. */
    private int history(CommandSender sender, UUID uuid, String name, int page) {
        MoneyConfig c = config.get();
        database.history(uuid, page, c.historyPageSize()).whenComplete((result, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not load transactions for " + name, error);
                sender.sendMessage(c.message("history-error"));
                return;
            }
            if (result.total() == 0) {
                sender.sendMessage(c.message("history-empty", Placeholder.unparsed(PLAYER, name)));
                return;
            }
            sendHistory(sender, c, uuid, name, result);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void sendHistory(CommandSender sender, MoneyConfig c, UUID uuid, String name, HistoryPage result) {
        sender.sendMessage(c.message("history-header",
                Placeholder.unparsed(PLAYER, name),
                Placeholder.unparsed(PAGE, String.valueOf(result.page())),
                Placeholder.unparsed("pages", String.valueOf(result.pages()))));
        for (Transaction tx : result.entries()) {
            sender.sendMessage(c.message("history-entry",
                    Placeholder.unparsed("time", c.timeFormat().format(tx.time())),
                    Placeholder.unparsed("type", tx.type().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("sign", sign(tx, uuid)),
                    Placeholder.unparsed("amount", c.format(tx.amount())),
                    Placeholder.unparsed("from", orDash(tx.fromName())),
                    Placeholder.unparsed("to", orDash(tx.toName())),
                    Placeholder.unparsed("actor", tx.actor())));
        }
    }

    /** "+" if the player received money, "-" if they paid, "=" if the balance was set outright. */
    static String sign(Transaction tx, UUID viewed) {
        if (tx.type() == TransactionType.SET || tx.type() == TransactionType.RESET) {
            return "=";
        }
        return viewed.equals(tx.fromUuid()) ? "-" : "+";
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    // --- admin ---------------------------------------------------------------

    private int give(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<Account> target = target(ctx);
        BigDecimal amount = target.isEmpty() ? null : amount(ctx);
        if (amount == null) {
            return 0;
        }
        Account account = target.get();
        EconomyResult result = economy.deposit(account.uuid(), amount, TransactionType.GIVE, sender.getName());
        if (result != EconomyResult.SUCCESS) {
            return fail(sender, result, account, StringArgumentType.getString(ctx, AMOUNT));
        }
        adminReply(sender, "give", account, economy.normalize(amount));
        return Command.SINGLE_SUCCESS;
    }

    private int take(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<Account> target = target(ctx);
        BigDecimal amount = target.isEmpty() ? null : amount(ctx);
        if (amount == null) {
            return 0;
        }
        Account account = target.get();
        EconomyResult result = economy.withdraw(account.uuid(), amount, TransactionType.TAKE, sender.getName());
        if (result == EconomyResult.INSUFFICIENT_FUNDS) {
            adminReply(sender, "take-insufficient", account, economy.normalize(amount));
            return 0;
        }
        if (result != EconomyResult.SUCCESS) {
            return fail(sender, result, account, StringArgumentType.getString(ctx, AMOUNT));
        }
        adminReply(sender, "take", account, economy.normalize(amount));
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<Account> target = target(ctx);
        BigDecimal amount = target.isEmpty() ? null : amount(ctx);
        if (amount == null) {
            return 0;
        }
        Account account = target.get();
        EconomyResult result = economy.set(account.uuid(), amount, TransactionType.SET, sender.getName());
        if (result != EconomyResult.SUCCESS) {
            return fail(sender, result, account, StringArgumentType.getString(ctx, AMOUNT));
        }
        adminReply(sender, "set", account, economy.normalize(amount));
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<Account> target = target(ctx);
        if (target.isEmpty()) {
            return 0;
        }
        Account account = target.get();
        BigDecimal start = economy.startingBalance();
        EconomyResult result = economy.set(account.uuid(), start, TransactionType.RESET, sender.getName());
        if (result != EconomyResult.SUCCESS) {
            return fail(sender, result, account, start.toPlainString());
        }
        adminReply(sender, "reset", account, start);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender sender) {
        MoneyConfig before = config.get();
        try {
            reload.run();
        } catch (RuntimeException e) {
            sender.sendMessage(before.message("reload-failed", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            return 0;
        }
        sender.sendMessage(config.get().message("reload"));
        return Command.SINGLE_SUCCESS;
    }

    /** Sends {@code <player>}, {@code <amount>} and the target's current {@code <balance>}. */
    private void adminReply(CommandSender sender, String key, Account target, BigDecimal amount) {
        MoneyConfig c = config.get();
        sender.sendMessage(c.message(key,
                Placeholder.unparsed(PLAYER, target.name()),
                Placeholder.unparsed("amount", c.format(amount)),
                Placeholder.unparsed("balance", c.format(economy.balance(target.uuid())))));
    }
}
