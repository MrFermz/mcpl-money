package dev.mcpl.money.testsupport;

import static org.mockito.Mockito.mock;

import com.destroystokyo.paper.SkinParts;
import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.poi.PoiType;
import io.papermc.paper.persistence.PersistentDataKey;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.world.damagesource.CombatEntry;
import io.papermc.paper.world.damagesource.FallLocationType;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attributable;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageEffect;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Stand-in for the server internals that {@link JavaPlugin}'s constructor calls, so tests can
 * construct the plugin the way Paper does. Registered in META-INF/services. Only
 * {@link #createPluginLifecycleEventManager} is used; everything else throws.
 *
 * <p>If a Paper upgrade adds methods to {@link InternalAPIBridge}, add them here (throwing).
 */
public final class TestInternalApiBridge implements InternalAPIBridge {

    @Override
    @SuppressWarnings("unchecked")
    public LifecycleEventManager<Plugin> createPluginLifecycleEventManager(JavaPlugin plugin, BooleanSupplier registrationCheck) {
        return mock(LifecycleEventManager.class);
    }

    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("Not available in unit tests");
    }

    @Override
    @SuppressWarnings("removal")
    public Biome constructLegacyCustomBiome() {
        throw unused();
    }

    @Override
    public CombatEntry createCombatEntry(LivingEntity entity, DamageSource damageSource, float damage) {
        throw unused();
    }

    @Override
    public CombatEntry createCombatEntry(DamageSource damageSource, float damage, FallLocationType fallLocationType, float fallDistance) {
        throw unused();
    }

    @Override
    public Predicate<CommandSourceStack> restricted(Predicate<CommandSourceStack> predicate, boolean restricted) {
        throw unused();
    }

    @Override
    public ResolvableProfile defaultMannequinProfile() {
        throw unused();
    }

    @Override
    public SkinParts.Mutable allSkinParts() {
        throw unused();
    }

    @Override
    public Component defaultMannequinDescription() {
        throw unused();
    }

    @Override
    public <MODERN, LEGACY> GameRule<LEGACY> legacyGameRuleBridge(GameRule<MODERN> rule, Function<LEGACY, MODERN> fromLegacy,
                                                                Function<MODERN, LEGACY> toLegacy, Class<LEGACY> legacyClass) {
        throw unused();
    }

    @Override
    public Set<Pose> validMannequinPoses() {
        throw unused();
    }

    @Override
    public PoiType.Occupancy createOccupancy(String name) {
        throw unused();
    }

    @Override
    public DamageSource.Builder createDamageSourceBuilder(DamageType damageType) {
        throw unused();
    }

    @Override
    public DamageEffect getDamageEffect(String key) {
        throw unused();
    }

    @Override
    public String getTranslationKey(EntityType entityType) {
        throw unused();
    }

    @Override
    public SpawnCategory getSpawnCategory(EntityType entityType) {
        throw unused();
    }

    @Override
    public ItemStack deserializeItem(byte[] data) {
        throw unused();
    }

    @Override
    public boolean hasDefaultEntityAttributes(NamespacedKey key) {
        throw unused();
    }

    @Override
    public Attributable getDefaultEntityAttributes(NamespacedKey key) {
        throw unused();
    }

    @Override
    public String getStatisticCriteriaKey(Statistic statistic) {
        throw unused();
    }

    @Override
    public ItemStack createEmptyStack() {
        throw unused();
    }

    @Override
    public Component resolveWithContext(Component component, CommandSender context, Entity scoreboardSubject, boolean bypassPermissions) {
        throw unused();
    }

    @Override
    public ComponentFlattener componentFlattener() {
        throw unused();
    }

    @Override
    public <C> PersistentDataKey<C> createPersistentDataKey(Key key, PersistentDataType<?, C> type) {
        throw unused();
    }
}
