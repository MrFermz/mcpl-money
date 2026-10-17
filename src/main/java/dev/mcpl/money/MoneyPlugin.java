package dev.mcpl.money;

import dev.mcpl.money.command.MoneyCommand;
import dev.mcpl.money.economy.Account;
import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.storage.DataSources;
import dev.mcpl.money.storage.Database;
import dev.mcpl.money.storage.SqlStorage;
import dev.mcpl.money.storage.StorageSettings;
import dev.mcpl.money.vault.VaultHook;
import io.papermc.paper.configuration.ServerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import javax.sql.DataSource;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class MoneyPlugin extends JavaPlugin implements Listener {

    private volatile MoneyConfig moneyConfig;
    private Economy economy;
    private Database database;

    @Override
    public void onEnable() {
        Server server = getServer();
        if (!isOnlineMode(server)) {
            getLogger().severe("This server is in offline mode. mcpl-money only supports online mode "
                    + "(or a proxy with online mode), because accounts are keyed by the player's Mojang UUID. Disabling.");
            server.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        try {
            moneyConfig = new MoneyConfig(getConfig(), ZoneId.systemDefault());
            database = openDatabase(moneyConfig.storage(), getDataFolder().toPath());
        } catch (Exception e) {
            // Refuse to start rather than run without the real balances.
            getLogger().log(Level.SEVERE, "Could not start: " + e.getMessage() + ". Disabling.", e);
            server.getPluginManager().disablePlugin(this);
            return;
        }

        // Covers /reload or a late plugin load while players are already online.
        for (Player player : server.getOnlinePlayers()) {
            economy.ensureAccount(player.getUniqueId(), player.getName());
        }
        server.getPluginManager().registerEvents(this, this);

        if (server.getPluginManager().getPlugin("Vault") != null) {
            VaultHook.register(server.getServicesManager(), this, economy, this::moneyConfig);
            getLogger().info("Registered as the Vault economy provider.");
        }

        MoneyCommand command = new MoneyCommand(economy, this::moneyConfig, server, database, this::reload, getLogger());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command.build(), "Check, send and manage money"));
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Could not close the database cleanly", e);
            }
            database = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        economy.ensureAccount(player.getUniqueId(), player.getName());
    }

    /**
     * Re-reads config.yml. Balances are kept; storage settings only take effect after a restart.
     *
     * @throws IllegalArgumentException if the new config is invalid (the old one stays active)
     */
    public void reload() {
        reloadConfig();
        MoneyConfig next = new MoneyConfig(getConfig(), ZoneId.systemDefault());
        moneyConfig = next;
        economy.updateSettings(next.economySettings());
    }

    public MoneyConfig moneyConfig() {
        return moneyConfig;
    }

    public Economy economy() {
        return economy;
    }

    /** Online mode on this server, or behind a proxy (Velocity/BungeeCord) that has online mode on. */
    static boolean isOnlineMode(Server server) {
        if (server.getOnlineMode()) {
            return true;
        }
        ServerConfiguration config = server.getServerConfig();
        return config.isProxyEnabled() && config.isProxyOnlineMode();
    }

    /** Connects, creates the tables if needed, loads every account and wires saving. */
    private Database openDatabase(StorageSettings settings, Path dataFolder) throws Exception {
        return openDatabase(new SqlStorage(dataSource(settings, dataFolder), settings.type()));
    }

    /** Package-private so tests can swap in their own data source. */
    DataSource dataSource(StorageSettings settings, Path dataFolder) {
        return DataSources.open(settings, dataFolder);
    }

    Database openDatabase(SqlStorage storage) throws Exception {
        try {
            storage.createSchema();
            List<Account> accounts = storage.loadAccounts();
            ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("mcpl-money-db").factory());
            Database db = new Database(storage, executor, getLogger(), 30);
            economy = new Economy(moneyConfig.economySettings(), Clock.systemUTC(), db::save);
            economy.loadAll(accounts);
            getLogger().info("Loaded " + accounts.size() + " accounts from " + storage.type().name().toLowerCase(java.util.Locale.ROOT) + ".");
            return db;
        } catch (Exception e) {
            storage.close();
            throw e;
        }
    }
}
