package self.research.ontology.common.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLogbackConfigTest {

    private static final List<String> SERVICES = List.of("ontology-auth", "ontology-editor", "ontology-gateway",
            "ontology-swrl", "ontology-plugin-service");

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("ontology-editor"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    @Test
    void everyServiceMasksEmailsInMessagesAndStackTraces() throws IOException {
        Path root = repositoryRoot();
        Assumptions.assumeTrue(root != null, "run from inside the repository");
        for (String service : SERVICES) {
            Path config = root.resolve(service).resolve("src/main/resources/logback-spring.xml");
            assertTrue(Files.exists(config), service + " has no logback-spring.xml");
            String xml = Files.readString(config);
            assertTrue(xml.contains("EmailMaskingMessageConverter") && xml.contains("EmailMaskingThrowableConverter"), service);
            assertTrue(xml.contains("%maskedMsg") && xml.contains("%maskedEx"), service);
            assertFalse(xml.replace("%maskedMsg", "").contains("%msg"), service + " still prints an unmasked message");
            assertFalse(xml.contains("%ex") || xml.contains("%wEx") || xml.contains("%throwable"),
                    service + " prints an unmasked stack trace");
        }
    }
}
