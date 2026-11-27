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
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache.CachedOntology;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopSaveWithJournalTest {

    @TempDir
    Path projectDir;

    private final StorageManager storage = mock(StorageManager.class);
    private final ProjectOntologyCache cache = mock(ProjectOntologyCache.class);
    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private DesktopEditJournal journal;
    private DesktopOntologyLoader loader;
    private OWLOntology open;
    private Path current;

    @BeforeEach
    void setUp() throws Exception {
        Path draft = projectDir.resolve("draft").resolve("ontology.draft.owl");
        current = projectDir.resolve("ontology.current.owl");
        when(storage.draftDir("p")).thenReturn(draft.getParent());
        when(storage.draftOntologyPath("p")).thenReturn(draft);
        when(storage.resolveProjectFile("p", "ontology.current.owl")).thenReturn(current);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        open = manager.createOntology(IRI.create("http://ex/s"));
        manager.addAxiom(open, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create("http://ex/s#A"))));
        try (OutputStream out = Files.newOutputStream(current)) {
            manager.saveOntology(open, new RDFXMLDocumentFormat(), out);
        }
        when(cache.has("p")).thenReturn(true);
        when(cache.get("p")).thenReturn(Optional.of(new CachedOntology(open, null, manager, false)));
        journal = new DesktopEditJournal(storage, 1);
        journal.loadedFrom("p", current);
        loader = new DesktopOntologyLoader();
        ReflectionTestUtils.setField(loader, "storageManager", storage);
        ReflectionTestUtils.setField(loader, "cache", cache);
        ReflectionTestUtils.setField(loader, "editJournal", journal);
    }

    @Test
    void saveWritesEditsThatAreOnlyInTheJournalIntoTheSavedFile() throws Exception {
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create("http://ex/s#A")),
                df.getOWLClass(IRI.create("http://ex/s#B")));
        List<OWLOntologyChange> changes = List.of(new AddAxiom(open, axiom));
        open.getOWLOntologyManager().applyChanges(changes);
        journal.append("p", open, changes);

        assertTrue(loader.saveProject("p"));

        OWLOntology saved = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(current.toFile());
        assertTrue(saved.containsAxiom(axiom));
        verify(storage).deleteDraft("p");
        verify(storage, never()).promoteDraft("p");
    }
}
