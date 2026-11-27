package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache.CachedOntology;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopJournalledEditTest {

    @TempDir
    java.nio.file.Path projectDir;

    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private final ProjectOntologyCache cache = mock(ProjectOntologyCache.class);
    private final OwlApiMutationPatcher patcher = mock(OwlApiMutationPatcher.class);
    private final DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
    private final DesktopEditJournal journal = mock(DesktopEditJournal.class);
    private final DesktopDraftCompactor compactor = mock(DesktopDraftCompactor.class);
    private OWLOntology ontology;
    private DesktopOwlApiMutationService desktop;
    private final OWLAxiom subClass = OWLManager.getOWLDataFactory().getOWLSubClassOfAxiom(
            OWLManager.getOWLDataFactory().getOWLClass(IRI.create("http://ex/A")),
            OWLManager.getOWLDataFactory().getOWLClass(IRI.create("http://ex/B")));

    @BeforeEach
    void setUp() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://ex/o"));
        when(cache.has("p")).thenReturn(true);
        when(cache.get("p")).thenReturn(Optional.of(new CachedOntology(ontology, null, manager, false)));
        desktop = new DesktopOwlApiMutationService(cache, patcher, mock(InMemorySparqlOntologyMutator.class),
                mock(StorageManager.class), mock(ProjectImportService.class), null, new DesktopDraftSaver(loader, cache, 60_000));
        ReflectionTestUtils.setField(desktop, "owlApiFirst", true);
        ReflectionTestUtils.setField(desktop, "editJournal", journal);
        ReflectionTestUtils.setField(desktop, "compactor", compactor);
    }

    private static List<MutationOp> op() {
        return List.of(new MutationOp("addSubClassOf", "http://ex/A", null, null, null, null, "http://ex/B", null, null,
                null, null, null, null, null, null));
    }

    private void patchAdds(OWLAxiom axiom) {
        when(patcher.tryPatch(eq("p"), anyList())).thenAnswer(inv -> {
            ontology.getOWLOntologyManager().addAxiom(ontology, axiom);
            return true;
        });
    }

    @Test
    void aJournalledEditReturnsWithoutRewritingTheWholeDraft() throws Exception {
        patchAdds(subClass);
        when(journal.append(eq("p"), eq(ontology), anyList())).thenReturn(true);

        assertTrue(desktop.tryApply("p", op(), "INSERT DATA { }"));

        verify(loader, never()).persistToDisk("p");
        verify(compactor).scheduleAfterEdit("p");
        verify(compactor).editStarting("p");
        verify(compactor).editFinished("p");
    }

    @Test
    void anEditTheJournalDoesNotTakeIsSavedInFullAsBefore() throws Exception {
        patchAdds(subClass);
        when(journal.append(eq("p"), eq(ontology), anyList())).thenReturn(false);

        assertTrue(desktop.tryApply("p", op(), "INSERT DATA { }"));

        verify(loader).persistToDisk("p");
    }

    @Test
    void anEditWhoseJournalRecordCannotBeWrittenIsUndoneAndRefused() throws Exception {
        patchAdds(subClass);
        when(journal.append(eq("p"), eq(ontology), anyList())).thenThrow(new IOException("disk full"));

        assertThrows(IllegalStateException.class, () -> desktop.tryApply("p", op(), "INSERT DATA { }"));

        assertFalse(ontology.containsAxiom(subClass));
        verify(compactor).editFinished("p");
    }

    @Test
    void anEditThatChangedNothingWritesNothing() throws Exception {
        when(patcher.tryPatch(eq("p"), anyList())).thenReturn(true);

        assertTrue(desktop.tryApply("p", op(), "INSERT DATA { }"));

        verify(loader, never()).persistToDisk("p");
        verify(journal, never()).append(any(), any(), anyList());
    }

    @Test
    void whileAnEarlierSaveIsStillFailingEditsAreSavedInFullNotJournalled() throws Exception {
        doAnswer(inv -> {
            throw new IOException("locked");
        }).doNothing().when(loader).persistToDisk("p");
        patchAdds(subClass);
        desktop.tryApply("p", op(), "INSERT DATA { }");
        patchAdds(df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create("http://ex/A")), df.getOWLClass(IRI.create("http://ex/C"))));

        desktop.tryApply("p", op(), "INSERT DATA { }");

        verify(journal, times(1)).append(any(), any(), anyList());
        verify(loader, times(2)).persistToDisk("p");
    }
}
