package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AssistantInsertionSnapperTest {

    @Mock
    private StorageManager storageManager;

    @TempDir
    Path tempDir;

    private AssistantInsertionSnapper snapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        snapper = new AssistantInsertionSnapper(storageManager);
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void draftScopeReadsTheDraftFileNotThePublicOne() throws Exception {
        Path publicFile = write("public.ttl", String.join("\n",
                ":Pizza a owl:Class .",
                ":Margherita a owl:Class .",
                ":Soup a owl:Class .",
                ""));
        Path draftFile = write("draft.ttl", String.join("\n",
                ":Pizza a owl:Class ;",
                "    rdfs:label \"Pizza\" .",
                ":Margherita a owl:Class .",
                ":Soup a owl:Class .",
                ""));

        StorageManager.ContentScope draftScope = new StorageManager.ContentScope(true, "u1");
        when(storageManager.resolveCodeViewFile("proj-1", "turtle", draftScope)).thenReturn(draftFile);
        when(storageManager.resolveCodeViewFile("proj-1", "turtle", StorageManager.ContentScope.publicScope()))
                .thenReturn(publicFile);

        OptionalLong draftResult = snapper.nextStatementBoundary("proj-1", "turtle", 1, draftScope);

        assertTrue(draftResult.isPresent(), "expected a boundary when reading the real draft file");
        assertEquals(2, draftResult.getAsLong());
    }

    @Test
    void publicScopeReadsThePublicFile() throws Exception {
        Path publicFile = write("public.ttl", String.join("\n",
                ":Pizza a owl:Class .",
                ":Margherita a owl:Class .",
                ":Soup a owl:Class .",
                ""));

        when(storageManager.resolveCodeViewFile("proj-1", "turtle", StorageManager.ContentScope.publicScope()))
                .thenReturn(publicFile);

        OptionalLong result = snapper.nextStatementBoundary("proj-1", "turtle", 1,
                StorageManager.ContentScope.publicScope());

        assertTrue(result.isEmpty(), "line 1 already sits on its own statement boundary in the public file");
    }
}
