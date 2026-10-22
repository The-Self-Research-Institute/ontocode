package self.research.ontology.reasoner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MongoTimeoutConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer reasoningMongoTimeouts(
            @Value("${ontocode.reasoning.mongo-server-selection-timeout-ms:3000}") long serverSelectionTimeoutMs,
            @Value("${ontocode.reasoning.mongo-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${ontocode.reasoning.mongo-read-timeout-ms:10000}") int readTimeoutMs) {
        return builder -> builder
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS));
    }
}
