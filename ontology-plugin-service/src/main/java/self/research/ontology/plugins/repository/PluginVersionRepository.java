package self.research.ontology.plugins.repository;

import self.research.ontology.plugins.model.PluginVersion;

import java.util.List;
import java.util.Optional;

public interface PluginVersionRepository {

    List<PluginVersion> findByPluginIdOrderByPublishedAtDesc(String pluginId);

    Optional<PluginVersion> findByPluginIdAndVersion(String pluginId, String version);

    PluginVersion save(PluginVersion version);

    void delete(PluginVersion version);

    void incrementDownloads(String pluginId, String version);
}
