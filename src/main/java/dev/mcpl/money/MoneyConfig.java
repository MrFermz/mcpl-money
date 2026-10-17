package dev.mcpl.money;

import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.storage.StorageSettings;
import dev.mcpl.money.storage.StorageType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of {@code config.yml}. Rebuilt on {@code /money reload}. */
public final class MoneyConfig {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final FileConfiguration config;
    private final StorageSettings storage;
    private final BigDecimal startingBalance;
    private final BigDecimal maxBalance;
    private final int decimals;
    private final String symbol;
    private final String currencyFormat;
    private final String nameSingular;
    private final String namePlural;
    private final DecimalFormat numberFormat;
    private final int topPageSize;
    private final int historyPageSize;
    private final DateTimeFormatter timeFormat;

    /** @throws IllegalArgumentException if {@code storage.type} or {@code history-time-format} is invalid */
    public MoneyConfig(FileConfiguration config, ZoneId zone) {
        this.config = config;
        this.storage = new StorageSettings(
                StorageType.fromConfig(config.getString("storage.type", "sqlite")),
                config.getString("storage.sqlite.file", "money.db"),
                config.getString("storage.postgresql.host", "localhost"),
                config.getInt("storage.postgresql.port", 5432),
                config.getString("storage.postgresql.database", "minecraft"),
                config.getString("storage.postgresql.username", "minecraft"),
                config.getString("storage.postgresql.password", ""),
                config.getInt("storage.postgresql.pool-size", 4));
        this.decimals = Math.clamp(config.getInt("currency.decimals", 2), 0, 8);
        this.startingBalance = parseNonNegative(config.getString("starting-balance", "0"), BigDecimal.ZERO);
        this.maxBalance = parseNonNegative(config.getString("max-balance", "1000000000000"), new BigDecimal("1000000000000"));
        this.symbol = config.getString("currency.symbol", "$");
        this.currencyFormat = config.getString("currency.format", "<symbol><amount>");
        this.nameSingular = config.getString("currency.name-singular", "dollar");
        this.namePlural = config.getString("currency.name-plural", "dollars");
        this.topPageSize = Math.clamp(config.getInt("top-page-size", 10), 1, 50);
        this.historyPageSize = Math.clamp(config.getInt("history-page-size", 10), 1, 50);
        this.timeFormat = DateTimeFormatter.ofPattern(config.getString("history-time-format", "yyyy-MM-dd HH:mm"))
                .withZone(zone);

        String pattern = decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals);
        this.numberFormat = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        this.numberFormat.setRoundingMode(RoundingMode.DOWN);
    }

    public StorageSettings storage() {
        return storage;
    }

    public Economy.Settings economySettings() {
        return new Economy.Settings(decimals, startingBalance, maxBalance);
    }

    public int decimals() {
        return decimals;
    }

    public String nameSingular() {
        return nameSingular;
    }

    public String namePlural() {
        return namePlural;
    }

    public int topPageSize() {
        return topPageSize;
    }

    public int historyPageSize() {
        return historyPageSize;
    }

    public DateTimeFormatter timeFormat() {
        return timeFormat;
    }

    /** Formats an amount for display, e.g. {@code $1,234.50}. */
    public String format(BigDecimal amount) {
        String number;
        synchronized (numberFormat) {
            number = numberFormat.format(amount);
        }
        return currencyFormat.replace("<symbol>", symbol).replace("<amount>", number);
    }

    /**
     * Builds a chat message from {@code messages.<key>}. The {@code <prefix>} tag is always
     * available; callers add their own placeholders.
     */
    public Component message(String key, TagResolver... placeholders) {
        String raw = config.getString("messages." + key);
        if (raw == null) {
            return Component.text("Missing message: messages." + key);
        }
        TagResolver prefix = Placeholder.parsed("prefix", config.getString("messages.prefix", ""));
        return MINI_MESSAGE.deserialize(raw, TagResolver.resolver(prefix, TagResolver.resolver(placeholders)));
    }

    private static BigDecimal parseNonNegative(String value, BigDecimal fallback) {
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            return parsed.signum() < 0 ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
