package self.research.ontology.owlEditor.plugin;

import org.semanticweb.owlapi.model.OWLOntology;

import java.util.List;
import java.util.Map;

/**
 * Base interface for all OntoCode plugins.
 * Plugins extend the functionality of the ontology editor.
 */
public interface Plugin {

    /**
     * Plugin metadata
     */
    class PluginMetadata {
        private String id;
        private String name;
        private String version;
        private String description;
        private String author;
        private String authorEmail;
        private String website;
        private List<String> tags;
        private PluginType type;
        private boolean enabled;
        private String iconUrl;
        private Map<String, String> settings;

        public enum PluginType {
            REASONER,           // Custom reasoning engine
            IMPORT_EXPORT,      // Import/export format support
            VISUALIZATION,      // Custom visualization
            VALIDATOR,          // Ontology validation
            TRANSFORMER,        // Ontology transformation
            QUERY,              // Custom query language
            UI_COMPONENT,       // Custom UI component
            UTILITY             // General utility
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        
        public String getAuthorEmail() { return authorEmail; }
        public void setAuthorEmail(String email) { this.authorEmail = email; }
        
        public String getWebsite() { return website; }
        public void setWebsite(String website) { this.website = website; }
        
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        
        public PluginType getType() { return type; }
        public void setType(PluginType type) { this.type = type; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getIconUrl() { return iconUrl; }
        public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
        
        public Map<String, String> getSettings() { return settings; }
        public void setSettings(Map<String, String> settings) { this.settings = settings; }
    }

    /**
     * Get plugin metadata
     */
    PluginMetadata getMetadata();

    /**
     * Initialize the plugin
     */
    void initialize() throws PluginException;

    /**
     * Shutdown the plugin
     */
    void shutdown() throws PluginException;

    /**
     * Check if plugin is compatible with current version
     */
    boolean isCompatible(String editorVersion);

    /**
     * Get plugin configuration schema (JSON Schema)
     */
    default String getConfigurationSchema() {
        return "{}";
    }

    /**
     * Configure plugin with settings
     */
    default void configure(Map<String, Object> settings) throws PluginException {
        // Default: no configuration needed
    }

    /**
     * Plugin exception
     */
    class PluginException extends Exception {
        public PluginException(String message) {
            super(message);
        }

        public PluginException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}