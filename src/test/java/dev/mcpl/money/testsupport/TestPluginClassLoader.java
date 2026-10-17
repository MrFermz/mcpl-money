package dev.mcpl.money.testsupport;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.net.URL;
import java.net.URLClassLoader;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Minimal Paper plugin class loader: loads the plugin's own classes itself (child-first) and
 * everything else from the test class path. {@link JavaPlugin}'s constructor requires this type.
 */
public final class TestPluginClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {

    private final String packagePrefix;
    private JavaPlugin plugin;

    public TestPluginClassLoader(URL pluginClasses, String packagePrefix, ClassLoader parent) {
        super(new URL[] {pluginClasses}, parent);
        this.packagePrefix = packagePrefix;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && name.startsWith(packagePrefix)) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException e) {
                    loaded = null; // not one of the plugin's classes (e.g. a test class)
                }
            }
            if (loaded == null) {
                return super.loadClass(name, resolve);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public PluginMeta getConfiguration() {
        return null;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries) throws ClassNotFoundException {
        return loadClass(name, resolve);
    }

    @Override
    public void init(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public JavaPlugin getPlugin() {
        return plugin;
    }

    @Override
    public PluginClassLoaderGroup getGroup() {
        return null;
    }
}
