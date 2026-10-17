package dev.mcpl.money.vault;

import dev.mcpl.money.MoneyConfig;
import dev.mcpl.money.economy.Economy;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/**
 * Registers the Vault provider. Kept in its own class so the Vault API classes are only loaded
 * when Vault (or VaultUnlocked) is installed.
 */
public final class VaultHook {

    private VaultHook() {
    }

    public static void register(ServicesManager services, Plugin plugin, Economy economy, Supplier<MoneyConfig> config) {
        services.register(net.milkbowl.vault.economy.Economy.class,
                new VaultEconomy(economy, config, plugin::isEnabled, plugin.getName()),
                plugin, ServicePriority.Normal);
    }
}
