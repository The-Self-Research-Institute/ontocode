package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import self.research.ontology.common.db.ExpiringTable;

@Configuration(proxyBeanMethods = false)
public class CacheExpiryConfiguration {

    @Bean
    ExpiringTable hierarchySnapshotExpiry() {
        return ExpiringTable.of("hierarchy_snapshot");
    }

    @Bean
    ExpiringTable topLevelClassCacheExpiry() {
        return ExpiringTable.of("top_level_class_cache");
    }
}
