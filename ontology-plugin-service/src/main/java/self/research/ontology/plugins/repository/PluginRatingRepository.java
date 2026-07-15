package self.research.ontology.plugins.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.PluginRating;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginRatingRepository extends MongoRepository<PluginRating, String> {

    /**
     * Find all ratings for a specific plugin
     */
    List<PluginRating> findByPluginId(String pluginId);

    /**
     * Find a user's rating for a specific plugin
     */
    Optional<PluginRating> findByPluginIdAndUserId(String pluginId, String userId);

    /**
     * Find all ratings by a specific user
     */
    List<PluginRating> findByUserId(String userId);

    /**
     * Count total ratings for a plugin
     */
    Long countByPluginId(String pluginId);

    /**
     * Find ratings with specific star count for a plugin
     */
    List<PluginRating> findByPluginIdAndStars(String pluginId, Integer stars);

    /**
     * Delete all ratings for a plugin (when plugin is deleted)
     */
    void deleteByPluginId(String pluginId);
}
