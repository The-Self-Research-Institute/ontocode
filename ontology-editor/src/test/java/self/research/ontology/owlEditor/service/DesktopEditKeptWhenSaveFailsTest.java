package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopEditKeptWhenSaveFailsTest {

    private static final String IRI = "http://ex/A";

    private final ProjectOntologyCache cache = mock(ProjectOntologyCache.class);
    private final OwlApiMutationPatcher patcher = mock(OwlApiMutationPatcher.class);
    private final DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
    private final ProjectImportService imports = mock(ProjectImportService.class);
    private final SparqlDatasetService datasets = mock(SparqlDatasetService.class);
    private DesktopDraftSaver saver;
    private DesktopOwlApiMutationService desktop;
    private OntologyMutationService mutations;

    @BeforeEach
    void setUp() {
        when(cache.has("p")).thenReturn(true);
        saver = new DesktopDraftSaver(loader, cache, 60_000);
        desktop = new DesktopOwlApiMutationService(cache, patcher, mock(InMemorySparqlOntologyMutator.class),
                mock(StorageManager.class), imports, null, saver);
        ReflectionTestUtils.setField(desktop, "owlApiFirst", true);
        Executor direct = Runnable::run;
        mutations = new OntologyMutationService(datasets, mock(OntologyIndexService.class), mock(ProjectMetadataService.class),
                mock(GraphGeneratingService.class), mock(TopLevelClassCacheService.class), mock(StorageManager.class), direct);
        ReflectionTestUtils.setField(mutations, "desktopOwlApiMutationService", desktop);
    }

    private static List<MutationOp> createClass() {
        return List.of(new MutationOp("createClass", IRI, "A", "http://www.w3.org/2002/07/owl#Thing", null, null, null,
                null, null, null, null, null, null, null, null));
    }

    @Test
    void anEditAppliedInMemoryStaysAppliedWhenTheDraftFileCannotBeWritten() throws Exception {
        when(patcher.tryPatch("p", createClass())).thenReturn(true);
        doThrow(new IOException("Could not replace ontology.draft.owl because another program has it open"))
                .when(loader).persistToDisk("p");

        assertTrue(desktop.tryApply("p", createClass(), "INSERT DATA { }"));

        assertTrue(saver.failure("p").isPresent());
        verify(cache, never()).evict("p");
    }

    @Test
    void anEditIsNotSentToTheTripleStoreInsteadWhenOnlyTheSaveFailed() throws Exception {
        when(patcher.tryPatch("p", createClass())).thenReturn(true);
        doThrow(new IOException("locked")).when(loader).persistToDisk("p");

        mutations.apply("p", createClass());

        verify(datasets, never()).execUpdate(anyString(), anyString());
    }

    @Test
    void anEditTheMemoryModelCannotTakeIsRefusedWhileTheTripleStoreIsBehind() {
        when(cache.has("p")).thenReturn(false);
        when(imports.isFusekiSyncPending("p")).thenReturn(true);

        assertThrows(RuntimeException.class, () -> mutations.apply("p", createClass()));
        assertThrows(IllegalStateException.class,
                () -> mutations.applyRawUpdate("p", "INSERT DATA { <http://ex/A> <http://ex/p> <http://ex/B> }", false, "u"));

        verify(datasets, never()).execUpdate(anyString(), anyString());
    }

    @Test
    void theTripleStorePathStaysOpenWhenItHoldsEverything() {
        when(imports.isFusekiSyncPending("p")).thenReturn(false);

        assertDoesNotThrow(() -> desktop.refuseIfTripleStoreIsBehind("p"));
    }

    @Test
    void theWebAppIsNeverRefused() {
        ReflectionTestUtils.setField(desktop, "owlApiFirst", false);
        when(imports.isFusekiSyncPending(any())).thenReturn(true);

        assertDoesNotThrow(() -> desktop.refuseIfTripleStoreIsBehind("p"));
    }
}
