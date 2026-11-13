package self.research.ontology.plugins.repository;

import self.research.ontology.plugins.model.PluginRating;

import java.util.List;
import java.util.Optional;

public interface PluginRatingRepository {

    List<PluginRating> findByPluginId(String pluginId);

    Optional<PluginRating> findByPluginIdAndUserId(String pluginId, String userId);

    Optional<PluginRating> findById(String id);

    PluginRating save(PluginRating rating);

    void delete(PluginRating rating);

    void incrementHelpful(String id);
}
