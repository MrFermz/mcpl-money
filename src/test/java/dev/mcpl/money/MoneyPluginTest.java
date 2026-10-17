package dev.mcpl.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.zaxxer.hikari.HikariDataSource;
import dev.mcpl.money.storage.DataSources;
import dev.mcpl.money.storage.SqlStorage;
import dev.mcpl.money.storage.StorageSettings;
import dev.mcpl.money.storage.StorageType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.configuration.ServerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Tests the plugin lifecycle without a server. The plugin is a Mockito mock that runs its real
 * methods, with the JavaPlugin plumbing (server, config, data folder, logger) stubbed out.
 */
class MoneyPluginTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path dataFolder;

    final Server server = mock(Server.class);
    final ServerConfiguration serverConfig = mock(ServerConfiguration.class);
    final PluginManager pluginManager = mock(PluginManager.class);
    final ServicesManager services = mock(ServicesManager.class);
    @SuppressWarnings("unchecked")
    final LifecycleEventManager<Plugin> lifecycle = mock(LifecycleEventManager.class);
    final List<LogRecord> logs = new ArrayList<>();
    final List<Player> online = new ArrayList<>();
    YamlConfiguration yaml;
    MoneyPlugin plugin;

    @BeforeEach
    void setUp() {
        when(server.getOnlineMode()).thenReturn(true);
        when(server.getServerConfig()).thenReturn(serverConfig);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getOnlinePlayers()).thenAnswer(inv -> (Collection<? extends Player>) List.copyOf(online));

        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        yaml = TestSupport.defaultYaml();
        plugin = mock(MoneyPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(server).when(plugin).getServer();
        doReturn(logger).when(plugin).getLogger();
        doReturn(dataFolder.toFile()).when(plugin).getDataFolder();
        doReturn(lifecycle).when(plugin).getLifecycleManager();
        doReturn("mcpl-money").when(plugin).getName();
        doNothing().when(plugin).saveDefaultConfig();
        doNothing().when(plugin).reloadConfig();
        doAnswerConfig();
    }

    private void doAnswerConfig() {
        org.mockito.Mockito.doAnswer(inv -> yaml).when(plugin).getConfig();
    }

    @AfterEach
    void tearDown() {
        plugin.onDisable();
    }

    private static Player player(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        return player;
    }

    private boolean logged(Level level, String text) {
        return logs.stream().anyMatch(r -> r.getLevel() == level && r.getMessage().contains(text));
    }

    @Test
    void canBeConstructedByPaperPluginClassLoader() throws Exception {
        java.net.URL classes = MoneyPlugin.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new dev.mcpl.money.testsupport.TestPluginClassLoader(
                classes, "dev.mcpl.money.", getClass().getClassLoader())) {
            Object instance = loader.loadClass(MoneyPlugin.class.getName()).getDeclaredConstructor().newInstance();

            assertThat(instance.getClass().getName()).isEqualTo(MoneyPlugin.class.getName());
            assertThat(loader.getPlugin()).isSameAs(instance);
            assertThat(loader.getConfiguration()).isNull();
            assertThat(loader.getGroup()).isNull();
            assertThat(loader.loadClass("java.lang.String", false, false, false)).isSameAs(String.class);
        }
    }

    @Test
    void onlineModeDetection() {
        assertThat(MoneyPlugin.isOnlineMode(server)).isTrue();

        when(server.getOnlineMode()).thenReturn(false);
        assertThat(MoneyPlugin.isOnlineMode(server)).isFalse();

        when(serverConfig.isProxyEnabled()).thenReturn(true);
        assertThat(MoneyPlugin.isOnlineMode(server)).isFalse();

        when(serverConfig.isProxyOnlineMode()).thenReturn(true);
        assertThat(MoneyPlugin.isOnlineMode(server)).isTrue();
    }

    @Test
    void refusesToRunInOfflineMode() {
        when(server.getOnlineMode()).thenReturn(false);

        plugin.onEnable();

        verify(pluginManager).disablePlugin(plugin);
        verify(plugin, never()).saveDefaultConfig();
        assertThat(logged(Level.SEVERE, "offline mode")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void enablesWithSqliteVaultAndCommands() throws Exception {
        Player alice = player(A, "Alice");
        online.add(alice);
        when(pluginManager.getPlugin("Vault")).thenReturn(mock(Plugin.class));

        plugin.onEnable();

        verify(pluginManager, never()).disablePlugin(any());
        assertThat(dataFolder.resolve("money.db")).exists();
        assertThat(plugin.economy().balance(A)).isEqualByComparingTo("100");
        assertThat(plugin.moneyConfig().storage().type()).isEqualTo(StorageType.SQLITE);
        verify(pluginManager).registerEvents(plugin, plugin);
        verify(services).register(eq(net.milkbowl.vault.economy.Economy.class), any(), eq(plugin), any());
        assertThat(logged(Level.INFO, "Loaded 0 accounts from sqlite")).isTrue();

        ArgumentCaptor<LifecycleEventHandler<ReloadableRegistrarEvent<Commands>>> handler =
                ArgumentCaptor.forClass(LifecycleEventHandler.class);
        verify(lifecycle).registerEventHandler(eq(LifecycleEvents.COMMANDS), handler.capture());
        ReloadableRegistrarEvent<Commands> event = mock(ReloadableRegistrarEvent.class);
        Commands commands = mock(Commands.class);
        when(event.registrar()).thenReturn(commands);
        handler.getValue().run(event);
        verify(commands).register(any(), eq("Check, send and manage money"));
    }

    @Test
    void enablesWithoutVault() {
        plugin.onEnable();

        verify(services, never()).register(any(), any(), any(), any());
    }

    @Test
    void savedBalancesSurviveRestart() {
        plugin.onEnable();
        plugin.onJoin(new PlayerJoinEvent(player(A, "Alice"), (net.kyori.adventure.text.Component) null));
        plugin.economy().deposit(A, new java.math.BigDecimal("5"), dev.mcpl.money.economy.TransactionType.GIVE, "test");
        plugin.onDisable();

        plugin.onEnable();

        assertThat(plugin.economy().balance(A)).isEqualByComparingTo("105");
    }

    @Test
    void disablesOnInvalidConfig() {
        yaml.set("storage.type", "mysql");

        plugin.onEnable();

        verify(pluginManager).disablePlugin(plugin);
        assertThat(logged(Level.SEVERE, "Unknown storage.type 'mysql'")).isTrue();
    }

    @Test
    void disablesAndClosesPoolWhenDatabaseFails() throws Exception {
        DataSource broken = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        doReturn(broken).when(plugin).dataSource(any(), any());

        plugin.onEnable();

        verify(pluginManager).disablePlugin(plugin);
        verify((AutoCloseable) broken).close();
        assertThat(logged(Level.SEVERE, "connection refused")).isTrue();
    }

    @Test
    void logsWhenDatabaseFailsToClose() throws Exception {
        HikariDataSource real = DataSources.open(
                new StorageSettings(StorageType.SQLITE, "money.db", null, 0, null, null, null, 0), dataFolder);
        DataSource failingClose = mock(DataSource.class,
                withSettings().extraInterfaces(AutoCloseable.class).defaultAnswer(delegatesTo(real)));
        doThrow(new IllegalStateException("close failed")).when((AutoCloseable) failingClose).close();
        doReturn(failingClose).when(plugin).dataSource(any(), any());
        plugin.onEnable();

        plugin.onDisable();

        assertThat(logged(Level.SEVERE, "Could not close the database cleanly")).isTrue();
        real.close();
    }

    @Test
    void openDatabaseClosesStorageOnFailure() throws Exception {
        SqlStorage storage = mock(SqlStorage.class);
        doThrow(new SQLException("no schema")).when(storage).createSchema();

        assertThatThrownBy(() -> plugin.openDatabase(storage)).hasMessage("no schema");
        verify(storage).close();
    }

    @Test
    void joiningCreatesAccount() {
        plugin.onEnable();

        plugin.onJoin(new PlayerJoinEvent(player(A, "Alice"), (net.kyori.adventure.text.Component) null));

        assertThat(plugin.economy().find(A)).isPresent();
    }

    @Test
    void reloadAppliesNewSettingsAndRejectsInvalidOnes() {
        plugin.onEnable();
        yaml.set("starting-balance", 250);

        plugin.reload();

        assertThat(plugin.economy().startingBalance()).isEqualByComparingTo("250");
        assertThat(plugin.moneyConfig().economySettings().startingBalance()).isEqualByComparingTo("250");

        MoneyConfig before = plugin.moneyConfig();
        yaml.set("storage.type", "nope");
        assertThatThrownBy(plugin::reload).isInstanceOf(IllegalArgumentException.class);
        assertThat(plugin.moneyConfig()).isSameAs(before);
    }

    @Test
    void disableWithoutEnableIsSafe() {
        plugin.onDisable();

        verify(services).unregisterAll(plugin);
    }

    @Test
    void realDataSourceIsUsedByDefault() throws Exception {
        try (var ds = (HikariDataSource) plugin.dataSource(
                new StorageSettings(StorageType.SQLITE, "x.db", null, 0, null, null, null, 0), dataFolder)) {
            assertThat(ds.getJdbcUrl()).contains("x.db");
        }
    }
}
