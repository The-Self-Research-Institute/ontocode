package self.research.ontology.plugins.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.PluginInstallation;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginInstallationRepository extends MongoRepository<PluginInstallation, String> {

    List<PluginInstallation> findByUserId(String userId);

    Optional<PluginInstallation> findByUserIdAndPluginId(String userId, String pluginId);

    Long countByPluginId(String pluginId);

    List<PluginInstallation> findByUserIdAndEnabledTrue(String userId);
}
