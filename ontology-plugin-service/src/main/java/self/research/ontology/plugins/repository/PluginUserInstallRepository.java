package self.research.ontology.plugins.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.PluginUserInstall;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginUserInstallRepository extends MongoRepository<PluginUserInstall, String> {

    /**
     * Find all installations for a specific plugin
     */
    List<PluginUserInstall> findByPluginId(String pluginId);

    /**
     * Find a user's installation record for a specific plugin
     */
    Optional<PluginUserInstall> findByPluginIdAndUserId(String pluginId, String userId);

    /**
     * Find all plugins installed by a user
     */
    List<PluginUserInstall> findByUserId(String userId);

    /**
     * Find all active installations for a plugin
     */
    List<PluginUserInstall> findByPluginIdAndIsActive(String pluginId, Boolean isActive);

    /**
     * Count total active installations for a plugin
     */
    Long countByPluginIdAndIsActive(String pluginId, Boolean isActive);

    /**
     * Count total installations (including past) for a plugin
     */
    Long countByPluginId(String pluginId);

    /**
     * Find users who installed a specific plugin (active installs only)
     */
    List<PluginUserInstall> findByIsActive(Boolean isActive);
}
