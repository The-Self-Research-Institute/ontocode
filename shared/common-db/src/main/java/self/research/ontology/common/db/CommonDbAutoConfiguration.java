package self.research.ontology.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;

@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class})
@ConditionalOnBean(DataSource.class)
@EnableScheduling
public class CommonDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper commonDbObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    FlywayMigrationStrategy tolerantFlywayMigration() {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(CommonDbAutoConfiguration.class)
                        .warn("[Database] Could not prepare the database schema, the service keeps running without it for now: {}",
                                e.getMessage());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionTemplate commonDbTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    @ConditionalOnMissingBean
    StoredFiles storedFiles(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new StoredFiles(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    DiskBlobStore diskBlobStore(StoredFiles files,
                                @Value("${ontocode.blobs.dir:${ontocode.data.dir:./data}/blobs}") Path root,
                                @Value("${ontocode.blobs.min-free-mb:1024}") long minFreeMb) {
        return new DiskBlobStore(files, root, minFreeMb * 1024 * 1024);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(RoutingBlobStore.class)
    BlobStore blobStore(JdbcTemplate jdbc, TransactionTemplate tx, StoredFiles files, DiskBlobStore disk,
                        @Value("${ontocode.blobs.storage:disk}") String storage) {
        return new RoutingBlobStore(new JdbcBlobStore(jdbc, tx, files), disk, storage, files);
    }

    @Bean
    @ConditionalOnProperty(name = "ontocode.blobs.sweep.enabled", havingValue = "true", matchIfMissing = true)
    BlobSweeper blobSweeper(DiskBlobStore disk, @Value("${ontocode.blobs.sweep.min-age-hours:6}") long minAgeHours) {
        return new BlobSweeper(disk, Duration.ofHours(minAgeHours));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "ontocode.db.expiry.enabled", havingValue = "true", matchIfMissing = true)
    ExpiryPurger expiryPurger(JdbcTemplate jdbc, ObjectProvider<ExpiringTable> tables,
                              @Value("${ontocode.db.expiry.batch-size:5000}") int batchSize) {
        return new ExpiryPurger(jdbc, tables, batchSize);
    }
}
