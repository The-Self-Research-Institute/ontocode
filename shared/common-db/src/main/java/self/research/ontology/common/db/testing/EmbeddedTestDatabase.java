package self.research.ontology.common.db.testing;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;

public final class EmbeddedTestDatabase {

    private static EmbeddedPostgres postgres;
    private static HikariDataSource dataSource;

    private EmbeddedTestDatabase() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        postgres.close();
                    } catch (IOException ignored) {
                    }
                }));
            } catch (IOException e) {
                throw new IllegalStateException("Could not start embedded PostgreSQL", e);
            }
            Flyway.configure().dataSource(postgres.getPostgresDatabase()).load().migrate();
            HikariDataSource pooled = new HikariDataSource();
            pooled.setJdbcUrl(postgres.getJdbcUrl("postgres", "postgres"));
            pooled.setUsername("postgres");
            pooled.setPassword("postgres");
            pooled.setMaximumPoolSize(5);
            dataSource = pooled;
        }
        return dataSource;
    }

    public static String jdbcUrl() {
        dataSource();
        return postgres.getJdbcUrl("postgres", "postgres");
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    public static TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
    }
}
