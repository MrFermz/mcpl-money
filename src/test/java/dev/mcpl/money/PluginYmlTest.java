package dev.mcpl.money;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mcpl.money.command.MoneyCommand;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the built plugin.yml (after Gradle fills in the version and libraries). */
class PluginYmlTest {

    YamlConfiguration yaml;

    @BeforeEach
    void load() throws Exception {
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/plugin.yml")), StandardCharsets.UTF_8)) {
            yaml = new YamlConfiguration();
            // Permission names contain dots, so use another character to separate paths.
            yaml.options().pathSeparator('/');
            yaml.load(reader);
        }
    }

    @Test
    void permissionDefaults() {
        ConfigurationSection permissions = yaml.getConfigurationSection("permissions");
        Map<String, String> defaults = permissions.getKeys(false).stream()
                .collect(Collectors.toMap(key -> key, key -> permissions.getString(key + "/default")));

        assertThat(defaults).containsExactlyInAnyOrderEntriesOf(Map.of(
                "money.balance", "true",
                "money.balance.others", "op",
                "money.pay", "true",
                "money.top", "true",
                "money.history", "true",
                "money.history.others", "op",
                "money.admin", "op"));
    }

    @Test
    void everyPermissionUsedInCodeIsDeclared() {
        var used = Arrays.stream(MoneyCommand.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("PERM_"))
                .map(PluginYmlTest::value)
                .toList();

        assertThat(yaml.getConfigurationSection("permissions").getKeys(false)).containsExactlyInAnyOrderElementsOf(used);
    }

    @Test
    void librariesAndVaultAreDeclared() {
        assertThat(yaml.getStringList("libraries")).hasSize(3).allMatch(lib -> lib.split(":").length == 3);
        assertThat(yaml.getStringList("softdepend")).containsExactly("Vault");
        assertThat(yaml.getString("main")).isEqualTo(MoneyPlugin.class.getName());
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
