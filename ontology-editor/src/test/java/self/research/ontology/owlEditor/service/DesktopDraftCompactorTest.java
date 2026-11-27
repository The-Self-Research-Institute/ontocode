package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.beans.factory.ObjectProvider;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache.CachedOntology;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopDraftCompactorTest {

    @TempDir
    Path projectDir;

    private final StorageManager storage = mock(StorageManager.class);
    private final ProjectOntologyCache cache = mock(ProjectOntologyCache.class);
    private final DesktopFusekiSyncScheduler sync = mock(DesktopFusekiSyncScheduler.class);
    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private DesktopEditJournal journal;
    private DesktopDraftCompactor compactor;
    private OWLOntology open;
    private Path draft;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        draft = projectDir.resolve("draft").resolve("ontology.draft.owl");
        when(storage.draftDir("p")).thenReturn(draft.getParent());
        when(storage.draftOntologyPath("p")).thenReturn(draft);
        Path current = projectDir.resolve("ontology.current.owl");
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        open = manager.createOntology(IRI.create("http://ex/c"));
        manager.addAxiom(open, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create("http://ex/c#A"))));
        try (OutputStream out = Files.newOutputStream(current)) {
            manager.saveOntology(open, new RDFXMLDocumentFormat(), out);
        }
        when(cache.get("p")).thenReturn(Optional.of(new CachedOntology(open, null, manager, false)));
        journal = new DesktopEditJournal(storage, 1);
        journal.loadedFrom("p", current);
        ObjectProvider<DesktopFusekiSyncScheduler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sync);
        compactor = new DesktopDraftCompactor(cache, journal, storage, provider, 60_000);
    }

    private OWLAxiom journalAnEdit(String name) throws Exception {
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create("http://ex/c#A")),
                df.getOWLClass(IRI.create("http://ex/c#" + name)));
        List<OWLOntologyChange> changes = List.of(new AddAxiom(open, axiom));
        open.getOWLOntologyManager().applyChanges(changes);
        journal.append("p", open, changes);
        return axiom;
    }

    @Test
    void foldingTheJournalWritesTheWholeModelToTheDraftAndEmptiesTheJournal() throws Exception {
        OWLAxiom axiom = journalAnEdit("B");

        assertTrue(compactor.compact("p"));

        assertFalse(journal.hasPending("p"));
        OWLOntology reopened = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(draft.toFile());
        assertTrue(reopened.containsAxiom(axiom));
        DesktopEditJournalTest.assertSameContent(open, reopened.axioms().collect(Collectors.toSet()));
        verify(sync).scheduleAfterMutation("p");
    }

    @Test
    void nothingIsFoldedWhileAnEditIsRunning() throws Exception {
        journalAnEdit("B");
        compactor.editStarting("p");

        assertFalse(compactor.compact("p"));
        assertTrue(journal.hasPending("p"));

        compactor.editFinished("p");
        assertTrue(compactor.compact("p"));
    }

    @Test
    void anEmptyJournalNeedsNoFolding() {
        assertFalse(compactor.compact("p"));
        verify(sync, never()).scheduleAfterMutation("p");
    }
}
