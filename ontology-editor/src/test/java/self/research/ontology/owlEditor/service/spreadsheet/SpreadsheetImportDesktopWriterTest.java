package self.research.ontology.owlEditor.service.spreadsheet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.service.DesktopFusekiSyncScheduler;
import self.research.ontology.owlEditor.service.DesktopOntologyLoader;
import self.research.ontology.owlEditor.service.InMemorySparqlOntologyMutator;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpreadsheetImportDesktopWriterTest {

    private static final String P = "p1";
    private static final List<String> UPDATES = List.of("DELETE DATA { <urn:a> <urn:b> <urn:c> }", "DELETE DATA { <urn:d> <urn:e> <urn:f> }");

    private final ProjectOntologyCache cache = mock(ProjectOntologyCache.class);
    private final InMemorySparqlOntologyMutator mutator = mock(InMemorySparqlOntologyMutator.class);
    private final DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
    private final ProjectImportService imports = mock(ProjectImportService.class);
    private final DesktopFusekiSyncScheduler scheduler = mock(DesktopFusekiSyncScheduler.class);
    private final OntologyMutationService mutations = mock(OntologyMutationService.class);
    private final StorageManager storage = mock(StorageManager.class);
    private SpreadsheetImportDesktopWriter writer;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    @BeforeEach
    void setUp() {
        writer = new SpreadsheetImportDesktopWriter(provider(cache), provider(mutator), provider(loader),
            provider(imports), provider(scheduler), provider(mutations), storage);
        ReflectionTestUtils.setField(writer, "desktopMode", true);
        ReflectionTestUtils.setField(writer, "owlApiFirst", true);
        when(cache.has(P)).thenReturn(true);
    }

    @Test
    void onTheWebItDoesNothing() throws Exception {
        ReflectionTestUtils.setField(writer, "desktopMode", false);
        assertFalse(writer.apply(P, UPDATES));
        writer.syncBeforeRead(P);
        verify(mutator, never()).tryApply(anyString(), anyString());
        verify(imports, never()).syncProjectToFuseki(anyString());
    }

    @Test
    void onDesktopAnUndoChangesTheInMemoryOntologyAndOnlyTheUnsavedDraftFile() throws Exception {
        when(mutator.tryApply(P, String.join(" ;\n", UPDATES))).thenReturn(true);

        assertTrue(writer.apply(P, UPDATES));

        verify(mutator).tryApply(P, String.join(" ;\n", UPDATES));
        verify(loader).persistToDisk(P);
        verify(loader, never()).saveProject(anyString());
        verify(imports).markFusekiSyncPendingPublic(P);
        verify(scheduler).scheduleAfterMutation(P);
        verify(mutations).invalidateEntityCaches(P, null, false);
        verify(mutations).bumpMainGraphRevision(P);
        verify(mutations).invalidateReasonerCaches(P);
    }

    @Test
    void whenTheInMemoryUpdateFailsNothingIsWritten() throws Exception {
        when(mutator.tryApply(anyString(), anyString())).thenReturn(false);
        assertFalse(writer.apply(P, UPDATES));
        verify(loader, never()).persistToDisk(anyString());
    }

    @Test
    void aProjectNotHeldInMemoryFallsBack() {
        when(cache.has(P)).thenReturn(false);
        assertFalse(writer.apply(P, UPDATES));
    }

    @Test
    void aPendingSyncIsFinishedBeforeTheImportReadsFuseki() {
        when(imports.isFusekiSyncPending(P)).thenReturn(true);
        writer.syncBeforeRead(P);
        verify(imports).syncProjectToFuseki(P);
    }

    @Test
    void noSyncWhenFusekiIsUpToDate() {
        when(imports.isFusekiSyncPending(P)).thenReturn(false);
        writer.syncBeforeRead(P);
        verify(imports, never()).syncProjectToFuseki(anyString());
    }
}
