package self.research.ontology.plugins.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.PluginVersion;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginVersionRepository extends MongoRepository<PluginVersion, String> {

    List<PluginVersion> findByPluginIdOrderByPublishedAtDesc(String pluginId);

    Optional<PluginVersion> findByPluginIdAndVersion(String pluginId, String version);

    List<PluginVersion> findByPluginIdAndDeprecatedFalseOrderByPublishedAtDesc(String pluginId);

    Optional<PluginVersion> findFirstByPluginIdOrderByPublishedAtDesc(String pluginId);
}
