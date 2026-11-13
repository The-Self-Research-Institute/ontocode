package self.research.ontology.plugins.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import self.research.ontology.plugins.model.Plugin;

import java.util.Optional;

public interface PluginRepository {

    Optional<Plugin> findByPluginId(String pluginId);

    Page<Plugin> findByCategory(String category, Pageable pageable);

    Page<Plugin> searchPlugins(String searchTerm, Pageable pageable);

    Page<Plugin> findActivePlugins(Pageable pageable);

    Plugin save(Plugin plugin);

    void delete(Plugin plugin);

    void incrementTotalDownloads(String pluginId);

    void setTotalDownloadsFromInstalls(String pluginId);

    void recomputeRating(String pluginId);
}
