package self.research.ontology.plugins.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.plugins.model.Plugin;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginRepository extends MongoRepository<Plugin, String> {

    Optional<Plugin> findByPluginId(String pluginId);

    List<Plugin> findByCategory(String category);

    Page<Plugin> findByCategory(String category, Pageable pageable);

    @Query("{ $or: [ " +
           "{ 'name': { $regex: ?0, $options: 'i' } }, " +
           "{ 'description': { $regex: ?0, $options: 'i' } }, " +
           "{ 'keywords': { $regex: ?0, $options: 'i' } }, " +
           "{ 'author': { $regex: ?0, $options: 'i' } } ] }")
    Page<Plugin> searchPlugins(String searchTerm, Pageable pageable);

    List<Plugin> findByVerified(Boolean verified);

    Page<Plugin> findByVerified(Boolean verified, Pageable pageable);

    @Query("{ 'deprecated': { $ne: true } }")
    Page<Plugin> findActivePlugins(Pageable pageable);
}
