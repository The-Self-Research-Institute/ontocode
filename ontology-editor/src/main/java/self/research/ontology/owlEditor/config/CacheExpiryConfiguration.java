package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import self.research.ontology.common.db.ExpiringTable;

@Configuration(proxyBeanMethods = false)
public class CacheExpiryConfiguration {

    @Bean
    public ExpiringTable hierarchySnapshotExpiry() {
        return ExpiringTable.of("hierarchy_snapshot");
    }

    @Bean
    public ExpiringTable topLevelClassCacheExpiry() {
        return ExpiringTable.of("top_level_class_cache");
    }

    @Bean
    public ExpiringTable assistantSessionExpiry() {
        return ExpiringTable.of("assistant_session");
    }

    @Bean
    public ExpiringTable assistantEditGroupExpiry() {
        return ExpiringTable.of("assistant_edit_group");
    }

    @Bean
    public ExpiringTable assistantAuditExpiry() {
        return ExpiringTable.of("assistant_audit");
    }

    @Bean
    public ExpiringTable assistantIdempotencyExpiry() {
        return ExpiringTable.of("assistant_idempotency");
    }
}
