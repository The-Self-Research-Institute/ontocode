package self.research.ontology.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class EmbeddedDatabaseSpringTest {

    private static final Path BLOBS = blobDirectory();

    private static Path blobDirectory() {
        try {
            Path dir = Files.createTempDirectory("auth-test-blobs");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir)));
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    @DynamicPropertySource
    static void embeddedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("ontocode.blobs.dir", () -> BLOBS.toString());
        registry.add("jwt.secret", () -> "dGVzdC1vbmx5LWp3dC1zZWNyZXQtdGVzdC1vbmx5LWp3dC1zZWNyZXQtdGVzdA==");
        registry.add("spring.mail.password", () -> "unused");
        registry.add("stripe.api.secret-key", () -> "sk_test_unused");
        registry.add("stripe.api.publishable-key", () -> "pk_test_unused");
        registry.add("stripe.webhook.secret", () -> "whsec_unused");
        registry.add("stripe.price.pro-monthly", () -> "price_test_pro_monthly");
        registry.add("stripe.price.pro-yearly", () -> "price_test_pro_yearly");
        registry.add("stripe.price.enterprise-monthly", () -> "price_test_ent_monthly");
        registry.add("stripe.price.enterprise-yearly", () -> "price_test_ent_yearly");
        registry.add("app.base-url", () -> "http://localhost:8080");
        registry.add("security.rate-limit.enabled", () -> "false");
        registry.add("app.email.enabled", () -> "false");
    }
}
