package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.plugin.*;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing plugins.
 * Handles plugin registration, loading, and lifecycle.
 */
@Service
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, Plugin.PluginMetadata.PluginType> pluginTypes = new ConcurrentHashMap<>();
    private final String EDITOR_VERSION = "1.0.0";

    @PostConstruct
    public void init() {
        log.info("Initializing Plugin Manager");
        loadBuiltInPlugins();
    }

    /**
     * Register a plugin
     */
    public void registerPlugin(Plugin plugin) throws Plugin.PluginException {
        Plugin.PluginMetadata metadata = plugin.getMetadata();
        String pluginId = metadata.getId();

        // Check compatibility
        if (!plugin.isCompatible(EDITOR_VERSION)) {
            throw new Plugin.PluginException(
                "Plugin " + metadata.getName() + " is not compatible with editor version " + EDITOR_VERSION
            );
        }

        // Check if already registered
        if (plugins.containsKey(pluginId)) {
            log.warn("Plugin {} already registered, replacing", pluginId);
        }

        // Initialize plugin
        try {
            plugin.initialize();
            plugins.put(pluginId, plugin);
            pluginTypes.put(pluginId, metadata.getType());
            log.info("Registered plugin: {} v{}", metadata.getName(), metadata.getVersion());
        } catch (Exception e) {
            throw new Plugin.PluginException("Failed to initialize plugin: " + metadata.getName(), e);
        }
    }

    /**
     * Unregister a plugin
     */
    public void unregisterPlugin(String pluginId) throws Plugin.PluginException {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new Plugin.PluginException("Plugin not found: " + pluginId);
        }

        try {
            plugin.shutdown();
            plugins.remove(pluginId);
            pluginTypes.remove(pluginId);
            log.info("Unregistered plugin: {}", plugin.getMetadata().getName());
        } catch (Exception e) {
            throw new Plugin.PluginException("Failed to shutdown plugin: " + plugin.getMetadata().getName(), e);
        }
    }

    /**
     * Get plugin by ID
     */
    public Plugin getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    /**
     * Get all plugins
     */
    public List<Plugin> getAllPlugins() {
        return new ArrayList<>(plugins.values());
    }

    /**
     * Get plugins by type
     */
    public List<Plugin> getPluginsByType(Plugin.PluginMetadata.PluginType type) {
        return plugins.values().stream()
            .filter(p -> p.getMetadata().getType() == type)
            .collect(Collectors.toList());
    }

    /**
     * Get all reasoner plugins
     */
    public List<ReasonerPlugin> getReasonerPlugins() {
        return plugins.values().stream()
            .filter(p -> p instanceof ReasonerPlugin)
            .map(p -> (ReasonerPlugin) p)
            .collect(Collectors.toList());
    }

    /**
     * Get all import/export plugins
     */
    public List<ImportExportPlugin> getImportExportPlugins() {
        return plugins.values().stream()
            .filter(p -> p instanceof ImportExportPlugin)
            .map(p -> (ImportExportPlugin) p)
            .collect(Collectors.toList());
    }

    /**
     * Get plugin metadata for all plugins
     */
    public List<Plugin.PluginMetadata> getAllPluginMetadata() {
        return plugins.values().stream()
            .map(Plugin::getMetadata)
            .collect(Collectors.toList());
    }

    /**
     * Enable plugin
     */
    public void enablePlugin(String pluginId) throws Plugin.PluginException {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new Plugin.PluginException("Plugin not found: " + pluginId);
        }

        Plugin.PluginMetadata metadata = plugin.getMetadata();
        metadata.setEnabled(true);
        log.info("Enabled plugin: {}", metadata.getName());
    }

    /**
     * Disable plugin
     */
    public void disablePlugin(String pluginId) throws Plugin.PluginException {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new Plugin.PluginException("Plugin not found: " + pluginId);
        }

        Plugin.PluginMetadata metadata = plugin.getMetadata();
        metadata.setEnabled(false);
        log.info("Disabled plugin: {}", metadata.getName());
    }

    /**
     * Configure plugin
     */
    public void configurePlugin(String pluginId, Map<String, Object> settings) throws Plugin.PluginException {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new Plugin.PluginException("Plugin not found: " + pluginId);
        }

        plugin.configure(settings);
        log.info("Configured plugin: {}", plugin.getMetadata().getName());
    }

    /**
     * Get plugin statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPlugins", plugins.size());
        stats.put("enabledPlugins", plugins.values().stream()
            .filter(p -> p.getMetadata().isEnabled())
            .count());

        // Count by type
        Map<Plugin.PluginMetadata.PluginType, Long> byType = plugins.values().stream()
            .collect(Collectors.groupingBy(
                p -> p.getMetadata().getType(),
                Collectors.counting()
            ));
        stats.put("pluginsByType", byType);

        return stats;
    }

    /**
     * Search plugins
     */
    public List<Plugin.PluginMetadata> searchPlugins(String query) {
        String lowerQuery = query.toLowerCase();
        return plugins.values().stream()
            .map(Plugin::getMetadata)
            .filter(m -> 
                m.getName().toLowerCase().contains(lowerQuery) ||
                m.getDescription().toLowerCase().contains(lowerQuery) ||
                (m.getTags() != null && m.getTags().stream()
                    .anyMatch(tag -> tag.toLowerCase().contains(lowerQuery)))
            )
            .collect(Collectors.toList());
    }

    /**
     * Load built-in plugins
     */
    private void loadBuiltInPlugins() {
        try {
            // Load ELK reasoner plugin (built-in)
            registerPlugin(new ELKReasonerPlugin());
            log.info("Loaded built-in ELK reasoner plugin");

            // Load JSON-LD import/export plugin (built-in)
            registerPlugin(new JsonLdPlugin());
            log.info("Loaded built-in JSON-LD plugin");

        } catch (Exception e) {
            log.error("Error loading built-in plugins", e);
        }
    }

    /**
     * Shutdown all plugins
     */
    public void shutdownAll() {
        log.info("Shutting down all plugins");
        for (Plugin plugin : plugins.values()) {
            try {
                plugin.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down plugin: " + plugin.getMetadata().getName(), e);
            }
        }
        plugins.clear();
        pluginTypes.clear();
    }
}