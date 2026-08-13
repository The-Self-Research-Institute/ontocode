package self.research.ontology.plugins.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.PluginUserInstall;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginUserInstallRepository extends MongoRepository<PluginUserInstall, String> {

    List<PluginUserInstall> findByPluginId(String pluginId);

    Optional<PluginUserInstall> findByPluginIdAndUserId(String pluginId, String userId);

    List<PluginUserInstall> findByUserId(String userId);

    List<PluginUserInstall> findByPluginIdAndIsActive(String pluginId, Boolean isActive);

    Long countByPluginIdAndIsActive(String pluginId, Boolean isActive);

    Long countByPluginId(String pluginId);

    List<PluginUserInstall> findByIsActive(Boolean isActive);
}
