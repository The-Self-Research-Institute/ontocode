package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.SWRLVariable;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesktopEditJournalTest {

    private static final String NS = "http://ex/j#";

    @TempDir
    Path projectDir;

    private final StorageManager storage = mock(StorageManager.class);
    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private DesktopEditJournal journal;
    private Path current;
    private Path draft;

    @BeforeEach
    void setUp() throws Exception {
        draft = projectDir.resolve("draft").resolve("ontology.draft.owl");
        when(storage.draftDir("p")).thenReturn(draft.getParent());
        when(storage.draftOntologyPath("p")).thenReturn(draft);
        current = projectDir.resolve("ontology.current.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology base = manager.createOntology(IRI.create("http://ex/j"));
        manager.addAxiom(base, df.getOWLDeclarationAxiom(clazz("A")));
        manager.addAxiom(base, df.getOWLSubClassOfAxiom(clazz("A"), clazz("Old")));
        try (OutputStream out = Files.newOutputStream(current)) {
            manager.saveOntology(base, new RDFXMLDocumentFormat(), out);
        }
        journal = new DesktopEditJournal(storage, 1);
        journal.loadedFrom("p", current);
    }

    private OWLClass clazz(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private static OWLOntology load(Path file) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(file.toFile());
    }

    private Set<OWLAxiom> reopenedAxioms() throws Exception {
        OWLOntology reopened = load(draft);
        new DesktopEditJournal(storage, 1).replay("p", reopened, reopened.getOWLOntologyManager());
        return reopened.axioms().collect(java.util.stream.Collectors.toSet());
    }

    static void assertSameContent(OWLOntology expected, Set<OWLAxiom> actual) {
        Set<OWLAxiom> wanted = expected.axioms().collect(java.util.stream.Collectors.toSet());
        assertEquals(withoutDeclarations(wanted), withoutDeclarations(actual));
        assertTrue(actual.containsAll(expected.axioms(org.semanticweb.owlapi.model.AxiomType.DECLARATION)
                .collect(java.util.stream.Collectors.toSet())));
    }

    private static Set<OWLAxiom> withoutDeclarations(Set<OWLAxiom> axioms) {
        return axioms.stream().filter(a -> !a.isOfType(org.semanticweb.owlapi.model.AxiomType.DECLARATION))
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<OWLOntologyChange> edit(OWLOntology open, OWLAxiom add, OWLAxiom remove) {
        List<OWLOntologyChange> changes = new java.util.ArrayList<>();
        if (remove != null) {
            changes.add(new RemoveAxiom(open, remove));
        }
        if (add != null) {
            changes.add(new AddAxiom(open, add));
        }
        open.getOWLOntologyManager().applyChanges(changes);
        return changes;
    }

    @Test
    void everyKindOfAxiomComesBackFromTheJournalExactlyAsItWasWritten() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create(NS + "q"));
        OWLNamedIndividual a = df.getOWLNamedIndividual(IRI.create(NS + "A"));
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
        OWLAnnotation note = df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("why", "en"));
        SWRLVariable v = df.getSWRLVariable(IRI.create(NS + "v"));
        Set<OWLAxiom> axioms = Set.of(
                df.getOWLSubClassOfAxiom(clazz("A"), clazz("B"), Set.of(note)),
                df.getOWLDeclarationAxiom(clazz("A")),
                df.getOWLDeclarationAxiom(a),
                df.getOWLClassAssertionAxiom(clazz("B"), a),
                df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(clazz("A"), clazz("B")), clazz("C")),
                df.getOWLDisjointClassesAxiom(clazz("A"), clazz("B"), clazz("C")),
                df.getOWLEquivalentClassesAxiom(clazz("A"), df.getOWLObjectSomeValuesFrom(p, clazz("B"))),
                df.getOWLNegativeObjectPropertyAssertionAxiom(p, x, a),
                df.getOWLSubPropertyChainOfAxiom(List.of(p, q), p),
                df.getOWLDataPropertyAssertionAxiom(df.getOWLDataProperty(IRI.create(NS + "age")), x, 30),
                df.getOWLAnnotationAssertionAxiom(clazz("A").getIRI(), df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("A")), Set.of(note)),
                df.getSWRLRule(Set.of(df.getSWRLClassAtom(clazz("A"), v)), Set.of(df.getSWRLClassAtom(clazz("B"), v))));

        Set<OWLAxiom> back = new HashSet<>(DesktopEditJournal.parse(DesktopEditJournal.render(axioms)));

        assertEquals(axioms, back);
    }

    @Test
    void anEditWrittenToTheJournalIsThereAfterARestartBeforeAnyDraftWasWritten() throws Exception {
        OWLOntology open = load(current);
        OWLAxiom added = df.getOWLSubClassOfAxiom(clazz("A"), clazz("New"));
        OWLAxiom removed = df.getOWLSubClassOfAxiom(clazz("A"), clazz("Old"));

        assertTrue(journal.append("p", open, edit(open, added, removed)));

        Set<OWLAxiom> reopened = reopenedAxioms();
        assertTrue(reopened.contains(added));
        assertFalse(reopened.contains(removed));
        assertEquals(open.axioms().collect(java.util.stream.Collectors.toSet()), reopened);
    }

    @Test
    void severalEditsReplayInTheOrderTheyWereMade() throws Exception {
        OWLOntology open = load(current);
        OWLAxiom x = df.getOWLSubClassOfAxiom(clazz("A"), clazz("X"));
        journal.append("p", open, edit(open, x, null));
        journal.append("p", open, edit(open, null, x));
        journal.append("p", open, edit(open, x, null));

        assertTrue(reopenedAxioms().contains(x));
        assertEquals(3, journal.lastSeq("p"));
    }

    @Test
    void aDraftWrittenUpToAnEditDropsThoseEditsFromTheJournalAndKeepsLaterOnes() throws Exception {
        OWLOntology open = load(current);
        journal.append("p", open, edit(open, df.getOWLSubClassOfAxiom(clazz("A"), clazz("One")), null));
        try (OutputStream out = Files.newOutputStream(draft)) {
            open.getOWLOntologyManager().saveOntology(open, new RDFXMLDocumentFormat(), out);
        }
        journal.written("p", 1);
        journal.append("p", open, edit(open, df.getOWLSubClassOfAxiom(clazz("A"), clazz("Two")), null));

        List<String> lines = Files.readAllLines(draft.resolveSibling(DesktopEditJournal.JOURNAL));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("2\t"));
        assertSameContent(open, reopenedAxioms());
    }

    @Test
    void aCrashAfterTheDraftWasReplacedButBeforeItsMarkerWasUpdatedReplaysHarmlessly() throws Exception {
        OWLOntology open = load(current);
        journal.append("p", open, edit(open, df.getOWLSubClassOfAxiom(clazz("A"), clazz("One")), null));
        journal.append("p", open, edit(open, null, df.getOWLSubClassOfAxiom(clazz("A"), clazz("Old"))));
        try (OutputStream out = Files.newOutputStream(draft)) {
            open.getOWLOntologyManager().saveOntology(open, new RDFXMLDocumentFormat(), out);
        }

        assertSameContent(open, reopenedAxioms());
    }

    @Test
    void aHalfWrittenLastRecordIsSkippedAndEarlierEditsStillComeBack() throws Exception {
        OWLOntology open = load(current);
        OWLAxiom kept = df.getOWLSubClassOfAxiom(clazz("A"), clazz("Kept"));
        journal.append("p", open, edit(open, kept, null));
        Files.writeString(draft.resolveSibling(DesktopEditJournal.JOURNAL), "2\tlineage\tdeadbeef\tAAAA",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Set<OWLAxiom> reopened = reopenedAxioms();
        assertTrue(reopened.contains(kept));
    }

    @Test
    void aJournalThatBelongsToAnotherDraftIsSetAsideNotReplayed() throws Exception {
        OWLOntology open = load(current);
        OWLAxiom foreign = df.getOWLSubClassOfAxiom(clazz("A"), clazz("Foreign"));
        journal.append("p", open, edit(open, foreign, null));
        Files.writeString(draft.resolveSibling(DesktopEditJournal.META), "lineage=another-draft\nseq=0\n");

        assertFalse(reopenedAxioms().contains(foreign));
        assertFalse(Files.exists(draft.resolveSibling(DesktopEditJournal.JOURNAL)));
        try (var files = Files.list(draft.getParent())) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith(DesktopEditJournal.JOURNAL + ".set-aside-")));
        }
    }

    @Test
    void smallOntologiesAndEditsWithBlankNodesAreNotJournalled() throws Exception {
        OWLOntology open = load(current);
        DesktopEditJournal strict = new DesktopEditJournal(storage, 1_000);
        strict.loadedFrom("p", current);
        assertFalse(strict.append("p", open, edit(open, df.getOWLSubClassOfAxiom(clazz("A"), clazz("Y")), null)));

        OWLAxiom blank = df.getOWLClassAssertionAxiom(clazz("A"), df.getOWLAnonymousIndividual());
        assertFalse(journal.append("p", open, edit(open, blank, null)));
    }

    @Test
    void withoutAKnownStartingFileNothingIsJournalled() throws Exception {
        OWLOntology open = load(current);
        DesktopEditJournal unknown = new DesktopEditJournal(storage, 1);

        assertFalse(unknown.append("p", open, edit(open, df.getOWLSubClassOfAxiom(clazz("A"), clazz("Z")), null)));
        assertFalse(Files.exists(draft));
    }
}
