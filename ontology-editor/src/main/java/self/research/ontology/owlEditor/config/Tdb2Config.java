package self.research.ontology.owlEditor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Configuration for TDB2 embedded triple store
 */
@Configuration
@EnableAsync
public class Tdb2Config {
    
    /**
     * Thread pool executor for metadata computation
     */
    @Bean(name = "metadataExecutor")
    public Executor metadataExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("metadata-");
        executor.initialize();
        return executor;
    }
}