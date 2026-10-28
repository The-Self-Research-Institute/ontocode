package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageManagerContainmentTest {

    @TempDir
    Path root;

    private StorageManager storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageManager(root.toString(), Mockito.mock(SparqlDatasetService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../other", "a/../../b", "/etc", "..\\other", "", " ", "a/b", "proj/../..", "."})
    void aProjectIdThatLeavesTheProjectsFolderIsRefused(String projectId) {
        assertThrows(IllegalArgumentException.class, () -> storage.projectDir(projectId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../x", "../../x/ontology.current.owl", "/etc/passwd", "sub/../../x", "..\\x"})
    void aFileNameThatLeavesTheProjectFolderIsRefused(String filename) {
        assertThrows(IllegalArgumentException.class, () -> storage.resolveProjectFile("proj-1", filename));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../projects/proj-1", "../../x", "a/b", "/abs", "", "x\u0000y"})
    void anUploadIdThatLeavesTheUploadFolderIsRefused(String uploadId) {
        assertThrows(IllegalArgumentException.class, () -> storage.chunkUploadDir(uploadId));
    }

    @Test
    void ordinaryNamesStillWork() throws Exception {
        Path dir = storage.prepareProjectDir("proj-51f64361--f8486548-5aa1-4aba-a38a-1a5dfcfd4410");

        assertTrue(Files.isDirectory(dir));
        assertEquals(dir.resolve("ontology.current.owl"),
                storage.resolveProjectFile("proj-51f64361--f8486548-5aa1-4aba-a38a-1a5dfcfd4410", "ontology.current.owl"));
        assertTrue(Files.isDirectory(storage.chunkUploadDir("3f2c1a9e-7b4d-4c1e-9d6a-0a1b2c3d4e5f")));
    }

    @Test
    void anUploadIdCannotReachAProjectFolder() throws Exception {
        Path victim = storage.prepareProjectDir("victim");
        Files.writeString(victim.resolve("ontology.current.owl"), "data");

        assertThrows(IllegalArgumentException.class, () -> storage.chunkUploadDir("../projects/victim"));

        assertTrue(Files.exists(victim.resolve("ontology.current.owl")));
    }
}
