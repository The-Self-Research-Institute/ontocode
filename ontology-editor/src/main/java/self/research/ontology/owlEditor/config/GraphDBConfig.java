package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for GraphDB triple store
 */
@Configuration
@EnableAsync
public class GraphDBConfig {
    
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
    
    /**
     * Thread pool executor for SPARQL queries
     */
    @Bean(name = "sparqlExecutor")
    public Executor sparqlExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sparql-");
        executor.initialize();
        return executor;
    }
}
