package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import self.research.ontology.owlEditor.service.Limit;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class JenaConfig {

    public JenaConfig(CapacityProperties capacity) {
    }

    @Bean(name = "metadataExecutor")
    public Executor metadataExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.METADATA_CORE_THREADS.value(), Limit.METADATA_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.METADATA_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.METADATA_QUEUE.value());
        executor.setThreadNamePrefix("metadata-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "sparqlExecutor")
    public Executor sparqlExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(Limit.SPARQL_CORE_THREADS.value(), Limit.SPARQL_MAX_THREADS.value()));
        executor.setMaxPoolSize(Limit.SPARQL_MAX_THREADS.value());
        executor.setQueueCapacity(Limit.SPARQL_QUEUE.value());
        executor.setThreadNamePrefix("sparql-");
        executor.initialize();
        return executor;
    }
}
