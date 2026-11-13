package self.research.ontology.common.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;

public final class PostgresTestSupport {

    private static EmbeddedPostgres postgres;

    private PostgresTestSupport() {
    }

    public static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
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
        }
        return postgres;
    }

    public static void register(DynamicPropertyRegistry registry) {
        EmbeddedPostgres pg = postgres();
        registry.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
