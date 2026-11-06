package self.research.ontology.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class, properties = "ontocode.blobs.sweep.enabled=false")
class BlobWiringTest {

    private static Path folder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        PostgresTestSupport.register(registry);
        folder = Files.createTempDirectory("common-db-blobs");
        registry.add("ontocode.blobs.dir", () -> folder.toString());
    }

    @Autowired
    BlobStore blobs;

    @Test
    void theApplicationGetsTheRoutingStoreAndWritesToDiskByDefault() throws IOException {
        assertInstanceOf(RoutingBlobStore.class, blobs);

        StoredFile stored = blobs.store("wired", "a.txt", "text/plain", Map.of("projectId", "p"), new ByteArrayInputStream("abc".getBytes()));

        assertEquals("disk", stored.storage());
        try (var files = Files.walk(folder)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals(stored.id())));
        }
        blobs.delete(stored.id());
    }
}
