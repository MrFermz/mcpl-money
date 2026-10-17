package dev.mcpl.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.storage.StorageSettings;
import dev.mcpl.money.storage.StorageType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MoneyConfigTest {

    @Test
    void readsBundledConfig() {
        MoneyConfig config = TestSupport.defaultConfig();

        assertThat(config.storage()).isEqualTo(new StorageSettings(StorageType.SQLITE, "money.db",
                "localhost", 5432, "minecraft", "minecraft", "", 4));
        assertThat(config.economySettings()).isEqualTo(
                new Economy.Settings(2, new BigDecimal("100"), new BigDecimal("1000000000000")));
        assertThat(config.decimals()).isEqualTo(2);
        assertThat(config.nameSingular()).isEqualTo("dollar");
        assertThat(config.namePlural()).isEqualTo("dollars");
        assertThat(config.topPageSize()).isEqualTo(10);
        assertThat(config.historyPageSize()).isEqualTo(10);
        assertThat(config.timeFormat().format(Instant.parse("2026-03-04T05:06:00Z"))).isEqualTo("2026-03-04 05:06");
        assertThat(config.format(new BigDecimal("1234.5"))).isEqualTo("$1,234.50");
    }

    @Test
    void usesDefaultsForEmptyConfig() {
        MoneyConfig config = new MoneyConfig(new YamlConfiguration(), ZoneOffset.UTC);

        assertThat(config.storage().type()).isEqualTo(StorageType.SQLITE);
        assertThat(config.economySettings()).isEqualTo(
                new Economy.Settings(2, BigDecimal.ZERO, new BigDecimal("1000000000000")));
        assertThat(config.format(BigDecimal.TEN)).isEqualTo("$10.00");
    }

    @Test
    void invalidOrNegativeAmountsFallBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("starting-balance", "lots");
        yaml.set("max-balance", "-5");

        Economy.Settings settings = new MoneyConfig(yaml, ZoneOffset.UTC).economySettings();

        assertThat(settings.startingBalance()).isEqualTo(BigDecimal.ZERO);
        assertThat(settings.maxBalance()).isEqualTo(new BigDecimal("1000000000000"));
    }

    @Test
    void wholeNumberCurrencyAndClamping() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("currency.decimals", 0);
        yaml.set("currency.symbol", "฿");
        yaml.set("currency.format", "<amount> <symbol>");
        yaml.set("top-page-size", 500);
        yaml.set("history-page-size", 0);

        MoneyConfig config = new MoneyConfig(yaml, ZoneOffset.UTC);

        assertThat(config.format(new BigDecimal("1250.99"))).isEqualTo("1,250 ฿");
        assertThat(config.topPageSize()).isEqualTo(50);
        assertThat(config.historyPageSize()).isEqualTo(1);

        yaml.set("currency.decimals", 12);
        assertThat(new MoneyConfig(yaml, ZoneOffset.UTC).decimals()).isEqualTo(8);
    }

    @Test
    void readsPostgresSettings() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("storage.type", "postgresql");
        yaml.set("storage.postgresql.host", "db");
        yaml.set("storage.postgresql.port", 6543);
        yaml.set("storage.postgresql.password", "secret");

        StorageSettings storage = new MoneyConfig(yaml, ZoneOffset.UTC).storage();

        assertThat(storage.type()).isEqualTo(StorageType.POSTGRESQL);
        assertThat(storage.host()).isEqualTo("db");
        assertThat(storage.port()).isEqualTo(6543);
        assertThat(storage.password()).isEqualTo("secret");
    }

    @Test
    void rejectsUnknownStorageType() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("storage.type", "mysql");

        assertThatThrownBy(() -> new MoneyConfig(yaml, ZoneOffset.UTC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mysql");
    }

    @Test
    void buildsMessagesWithPrefixAndPlaceholders() {
        MoneyConfig config = TestSupport.defaultConfig();

        assertThat(TestSupport.plain(config.message("balance-self", Placeholder.unparsed("balance", "$5.00"))))
                .isEqualTo("[Money] Your balance: $5.00");
        assertThat(TestSupport.plain(config.message("does-not-exist")))
                .isEqualTo("Missing message: messages.does-not-exist");
    }

    @Test
    void playerTextIsNotParsedAsTags() {
        MoneyConfig config = TestSupport.defaultConfig();

        assertThat(TestSupport.plain(config.message("pay-self-test-missing"))).startsWith("Missing");
        assertThat(TestSupport.plain(config.message("player-not-found", Placeholder.unparsed("player", "<red>x"))))
                .isEqualTo("[Money] No account found for <red>x.");
    }
}
