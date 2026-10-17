package dev.mcpl.money;

import static org.mockito.Mockito.mockingDetails;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Shared helpers for tests. */
public final class TestSupport {

    private TestSupport() {
    }

    /** The plugin's bundled config.yml. */
    public static YamlConfiguration defaultYaml() {
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(TestSupport.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MoneyConfig config(YamlConfiguration yaml) {
        return new MoneyConfig(yaml, ZoneOffset.UTC);
    }

    public static MoneyConfig defaultConfig() {
        return config(defaultYaml());
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Every Component message sent to a mocked sender, as plain text, in order. */
    public static List<String> messages(Object sender) {
        return mockingDetails(sender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .filter(i -> i.getArguments().length == 1 && i.getArguments()[0] instanceof Component)
                .map(i -> plain((Component) i.getArguments()[0]))
                .toList();
    }
}
