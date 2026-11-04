package self.research.ontology.plugins.service;

public class PluginOwnershipException extends RuntimeException {
    public PluginOwnershipException(String pluginId) {
        super("Plugin " + pluginId + " belongs to another publisher");
    }
}
