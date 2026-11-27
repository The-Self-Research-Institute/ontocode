package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class InterruptedSaveRecoveryTest {

    @TempDir
    Path dir;

    @Test
    void aProjectFileLeftAsideByACrashIsBackBeforeTheEditorServesRequests() throws Exception {
        StorageManager storage = new StorageManager(dir.toString(), mock(SparqlDatasetService.class));
        Path target = Files.createDirectories(storage.projectDir("proj-1")).resolve("ontology.current.owl");
        Files.writeString(target, "saved ontology");
        Path aside = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".old.staged");
        Files.move(target, aside);

        new InterruptedSaveRecovery(storage).recoverInterruptedSaves();

        assertEquals("saved ontology", Files.readString(target));
        assertFalse(Files.exists(aside));
    }
}
