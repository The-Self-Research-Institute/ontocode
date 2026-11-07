package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class MergeTempFilesCleanupTest {

    @Mock
    private StorageManager storageManager;
    @Mock
    private SparqlDatasetService datasetService;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(storageManager.projectDir("p")).thenReturn(dir);
    }

    private long count(String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void liveExportThatThrowsLeavesNoMergeLiveFile() throws Exception {
        Files.writeString(dir.resolve("ontology.dirty"), "");
        doThrow(new IllegalStateException("store down")).when(datasetService)
                .exportDatasetToStream(anyString(), any(RDFFormat.class), any(OutputStream.class));
        MergeLiveSource source = new MergeLiveSource(storageManager, datasetService);
        assertThrows(IllegalStateException.class, () -> source.export("p"));
        assertEquals(0, count("merge-live-"));
    }

    @Test
    void draftExportThatThrowsLeavesNoMergeLiveFile() throws Exception {
        when(datasetService.getDraftGraphUri("p", "u")).thenReturn("urn:g");
        when(datasetService.exportNamedGraph(anyString(), anyString(), any(RDFFormat.class)))
                .thenThrow(new IllegalStateException("store down"));
        MergeLiveSource source = new MergeLiveSource(storageManager, datasetService);
        assertThrows(IllegalStateException.class, () -> source.exportDraft("p", "u"));
        assertEquals(0, count("merge-live-"));
    }

    @Test
    void aSuccessfulLiveExportKeepsItsFileForTheCaller() throws Exception {
        Files.writeString(dir.resolve("ontology.dirty"), "");
        MergeLiveSource source = new MergeLiveSource(storageManager, datasetService);
        Path exported = source.export("p").orElseThrow();
        assertEquals(1, count("merge-live-"));
        Files.deleteIfExists(exported);
    }

    @Test
    void aCorruptCheckpointRestoreLeavesNoRestoreFileAndKeepsTheTarget() throws Exception {
        MergeCheckpointStore store = new MergeCheckpointStore(storageManager);
        Path original = dir.resolve("source.owl");
        Files.writeString(original, "a".repeat(200_000) + System.nanoTime() + new java.util.Random().ints(5000).boxed().toList());
        store.saveBefore("p", "cp1", original);
        Path stored = dir.resolve("merge-checkpoints").resolve("before-cp1.owl.gz");
        byte[] bytes = Files.readAllBytes(stored);
        Files.write(stored, Arrays.copyOf(bytes, bytes.length / 2));
        Path target = dir.resolve("target.owl");
        Files.writeString(target, "keep me");
        assertThrows(IOException.class, () -> store.restoreTo("p", "cp1", true, target));
        assertEquals("keep me", Files.readString(target));
        assertEquals(0, count("target.owl.restore"));
    }

    @Test
    void aCheckpointThatFailsLeavesNoPartialFile() throws Exception {
        MergeCheckpointStore store = new MergeCheckpointStore(storageManager);
        assertThrows(IOException.class, () -> store.saveBefore("p", "cp2", dir.resolve("does-not-exist.owl")));
        try (Stream<Path> files = Files.list(dir.resolve("merge-checkpoints"))) {
            assertEquals(0, files.count());
        }
    }
}
