package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;

import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftMergeEndToEndTest {

    private static final String NS = "http://ex.org/";
    private static final String USER = "mongo-1";

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;
    @Mock
    private OntologyMutationService mutations;
    @Mock
    private DraftTrackingService draftTracking;
    @Mock
    private DraftCopyService draftCopies;

    @TempDir
    Path dir;

    private OntologyMergeService service;
    private SailRepository draftGraph;
    private Path storedFile;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);
        storedFile = dir.resolve("stored.owl");
        write(storedFile, "target", "Base", "BaseOnly");
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(storedFile));
        when(storageManager.projectDir("proj")).thenReturn(dir);
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        doNothing().when(metadataService).writeMeta(anyString(), any());
        when(draftCopies.isReady("proj", USER)).thenReturn(true);
        when(datasetService.getDraftGraphUri("proj", USER)).thenReturn("urn:draft");

        draftGraph = new SailRepository(new MemoryStore());
        draftGraph.init();
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            connection.add(modelOf(storedFile));
        }
        when(datasetService.exportNamedGraph(eq("proj"), eq("urn:draft"), any(RDFFormat.class))).thenAnswer(inv -> {
            StringWriter out = new StringWriter();
            Rio.write(contentOf(draftGraph), out, RDFFormat.RDFXML);
            return out.toString();
        });
        doAnswer(inv -> {
            try (RepositoryConnection connection = draftGraph.getConnection()) {
                connection.prepareUpdate((String) inv.getArgument(1)).execute();
            }
            return null;
        }).when(mutations).applyRawUpdate(eq("proj"), anyString(), eq(true), eq(USER));

        ReflectionTestUtils.setField(service, "liveSource", new MergeLiveSource(storageManager, datasetService));
        ReflectionTestUtils.setField(service, "draftApplier",
                new DraftMergeApplier(mutations, draftTracking, historyService, draftCopies, new ProjectWriteLockRegistry()));
    }

    @AfterEach
    void tearDown() {
        draftGraph.shutDown();
    }

    private static void write(Path path, String name, String shared, String only) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + name));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + shared))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + only))));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), IRI.create(NS + only),
                df.getOWLLiteral(only)));
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private static Model modelOf(Path file) throws Exception {
        return Rio.parse(Files.newInputStream(file), "", RDFFormat.RDFXML);
    }

    private static Model contentOf(SailRepository repository) {
        Model content = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.getStatements(null, null, null).forEach(content::add);
        }
        return content;
    }

    private Model draft() {
        return contentOf(draftGraph);
    }

    private boolean hasClass(String name) {
        return draft().subjects().stream().anyMatch(subject -> subject.stringValue().equals(NS + name));
    }

    private void userAddsAClass(String name) {
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            connection.prepareUpdate("INSERT DATA { <" + NS + name + "> a <http://www.w3.org/2002/07/owl#Class> }").execute();
        }
    }

    private void userRemovesAClass(String name) {
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            connection.prepareUpdate("DELETE WHERE { <" + NS + name + "> ?p ?o }").execute();
        }
    }

    private HistoryChange merge(String sourceName, String only) throws Exception {
        Path source = dir.resolve(sourceName + ".owl");
        write(source, sourceName, "Base", only);
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.SIMPLE_UNION);
        service.mergeOntologies("src", source, "proj", null, null, options, USER, "real@x.org", true);
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(historyService, org.mockito.Mockito.atLeastOnce()).recordEdit(eq("proj"), eq(USER), eq("real@x.org"),
                eq("ontologyMerge"), anyString(), eq("Ontology merge"), any(), any(), anyString(), any(), any(), eq(true),
                origin.capture());
        ChangeOrigin last = origin.getAllValues().get(origin.getAllValues().size() - 1);
        assertTrue(last.exact() != null, "the draft merge recorded no exact change");
        Map<String, Object> stamped = new HashMap<>();
        last.exact().stamp(stamped, "e" + origin.getAllValues().size());
        HistoryChange change = new HistoryChange();
        change.setId("m" + origin.getAllValues().size());
        change.setOperationType("ontologyMerge");
        change.setEntityIRI(OntologyMergeService.MERGE_IRI_PREFIX + "x");
        change.setDraft(true);
        change.setUserId(USER);
        change.setExactDeleted((String) stamped.get("exactDeleted"));
        change.setExactInserted((String) stamped.get("exactInserted"));
        return change;
    }

    private void apply(Plan plan) {
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            for (String update : plan.rawUpdates()) {
                connection.prepareUpdate(update).execute();
            }
        }
    }

    @Test
    void aDraftMergeChangesTheDraftAndLeavesThePublicFileAlone() throws Exception {
        String before = Files.readString(storedFile);
        merge("one", "OneOnly");
        assertTrue(hasClass("OneOnly"));
        assertEquals(before, Files.readString(storedFile));
        verify(importService, never()).trySubmitImport(anyString(), any());
    }

    @Test
    void aDraftMergeIsRecordedInTheDraftAndTrackedForSave() throws Exception {
        merge("one", "OneOnly");
        ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> session = ArgumentCaptor.forClass(String.class);
        verify(draftTracking).recordDraft(eq("proj"), eq(USER), eq("real@x.org"), eq(OntologyMergeService.MERGE_OPERATION),
                data.capture(), session.capture());
        assertTrue(session.getValue().startsWith(DraftMergeApplier.SESSION_PREFIX));
        assertTrue(((String) data.getValue().get("iri")).startsWith(OntologyMergeService.MERGE_IRI_PREFIX));
        java.util.Map<?, ?> summary = (java.util.Map<?, ?>) data.getValue().get(DraftMergeApplier.SUMMARY);
        assertTrue(summary.get("kinds").toString().contains("OneOnly"), String.valueOf(summary));
    }

    @Test
    void anEditMadeBetweenTwoDraftMergesSurvivesTheSecondMerge() throws Exception {
        merge("one", "OneOnly");
        userAddsAClass("MyClass");
        merge("two", "TwoOnly");
        assertTrue(hasClass("MyClass"), "the draft edit was lost by the second merge");
        assertTrue(hasClass("OneOnly"));
        assertTrue(hasClass("TwoOnly"));
    }

    @Test
    void draftMergesUndoAndRedoInOrderAroundTheUsersEdit() throws Exception {
        Model original = draft();
        HistoryChange first = merge("one", "OneOnly");
        Model afterFirst = draft();
        userAddsAClass("MyClass");
        HistoryChange second = merge("two", "TwoOnly");
        Model afterSecond = draft();

        RollbackMutationPlanner planner = new RollbackMutationPlanner();
        apply(planner.planUndo(second, List.of()));
        assertFalse(hasClass("TwoOnly"));
        assertTrue(hasClass("MyClass"), "undoing the second merge removed the user's class");
        assertTrue(hasClass("OneOnly"));

        userRemovesAClass("MyClass");
        apply(planner.planUndo(first, List.of()));
        assertTrue(Models.isomorphic(draft(), original), "undo did not return the draft to its starting state");

        apply(planner.planRedo(first, List.of()));
        assertTrue(Models.isomorphic(draft(), afterFirst), "redo of the first merge did not restore it");
        userAddsAClass("MyClass");
        apply(planner.planRedo(second, List.of()));
        assertTrue(Models.isomorphic(draft(), afterSecond), "redo of the second merge did not restore it");
    }

    @Test
    void aDraftThatIsNotReadyIsRefusedAndNothingChanges() throws Exception {
        when(draftCopies.isReady("proj", USER)).thenReturn(false);
        Model before = draft();
        assertThrows(DraftNotReadyException.class, () -> merge("one", "OneOnly"));
        assertTrue(Models.isomorphic(draft(), before));
        verify(historyService, never()).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void theDraftCopyIsTheStartingPointNotTheStoredFile() throws Exception {
        userAddsAClass("DraftOnly");
        merge("one", "OneOnly");
        assertTrue(hasClass("DraftOnly"), "the merge ignored what was already in the draft");
        assertFalse(Files.readString(storedFile).contains("DraftOnly"));
    }

    @Test
    void thePublicPathIsUnchangedWhenTheMergeIsNotADraftMerge() throws Exception {
        Path source = dir.resolve("pub.owl");
        write(source, "pub", "Base", "PubOnly");
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.SIMPLE_UNION);
        service.mergeOntologies("src", source, "proj", null, null, options, "u1", "User", false);
        assertTrue(Files.readString(storedFile).contains("PubOnly"));
        assertFalse(hasClass("PubOnly"));
    }
}
