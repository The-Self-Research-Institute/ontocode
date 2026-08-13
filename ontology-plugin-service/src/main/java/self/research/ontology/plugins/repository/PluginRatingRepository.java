package self.research.ontology.plugins.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.PluginRating;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginRatingRepository extends MongoRepository<PluginRating, String> {

    List<PluginRating> findByPluginId(String pluginId);

    Optional<PluginRating> findByPluginIdAndUserId(String pluginId, String userId);

    List<PluginRating> findByUserId(String userId);

    Long countByPluginId(String pluginId);

    List<PluginRating> findByPluginIdAndStars(String pluginId, Integer stars);

    void deleteByPluginId(String pluginId);
}
