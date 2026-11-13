package self.research.ontology.plugins.repository;

import self.research.ontology.plugins.model.PluginUserInstall;

import java.util.List;
import java.util.Optional;

public interface PluginUserInstallRepository {

    Optional<PluginUserInstall> findByPluginIdAndUserId(String pluginId, String userId);

    List<PluginUserInstall> findByUserId(String userId);

    Long countByPluginId(String pluginId);

    Long countByPluginIdAndIsActive(String pluginId, Boolean isActive);

    void recordInstall(String pluginId, String userId, String username, String version);

    boolean recordUninstall(String pluginId, String userId);
}
