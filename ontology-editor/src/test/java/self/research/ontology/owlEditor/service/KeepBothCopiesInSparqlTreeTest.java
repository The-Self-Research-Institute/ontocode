package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQueryResult;
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
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static self.research.ontology.owlEditor.service.KeepBothPizzaFixture.ENTITY;
import static self.research.ontology.owlEditor.service.KeepBothPizzaFixture.P;
import static self.research.ontology.owlEditor.service.KeepBothPizzaFixture.SUFFIX;

class KeepBothCopiesInSparqlTreeTest {

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
    @Mock
    private TopLevelClassCacheService topLevelCache;

    @TempDir
    Path dir;

    private OntologyMergeService service;
    private SailRepository draftGraph;
    private final AtomicBoolean slowRootScan = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);
        Path storedFile = dir.resolve("stored.owl");
        KeepBothPizzaFixture.writeTarget(storedFile);
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(storedFile));
        when(storageManager.projectDir("proj")).thenReturn(dir);
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        when(draftCopies.isReady("proj", USER)).thenReturn(true);
        when(datasetService.getDraftGraphUri("proj", USER)).thenReturn("urn:draft");

        draftGraph = new SailRepository(new MemoryStore());
        draftGraph.init();
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            connection.add(Rio.parse(Files.newInputStream(storedFile), "", RDFFormat.RDFXML));
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
        when(datasetService.execSelect(eq("proj"), anyString())).thenAnswer(inv -> select(inv.getArgument(1)));

        ReflectionTestUtils.setField(service, "liveSource", new MergeLiveSource(storageManager, datasetService));
        ReflectionTestUtils.setField(service, "draftApplier",
                new DraftMergeApplier(mutations, draftTracking, historyService, draftCopies, new ProjectWriteLockRegistry()));
    }

    @AfterEach
    void tearDown() {
        SparqlQueryContext.clear();
        draftGraph.shutDown();
    }

    private TupleQueryResult select(String query) throws InterruptedException {
        if (query.contains("ORDER BY ?c") && slowRootScan.getAndSet(false)) {
            Thread.sleep(10_100);
        }
        try (RepositoryConnection connection = draftGraph.getConnection();
             TupleQueryResult rows = connection.prepareTupleQuery(query).evaluate()) {
            List<String> names = rows.getBindingNames();
            List<BindingSet> all = QueryResults.asList(rows);
            return new SimpleTupleQueryResult(names, all);
        }
    }

    private static Model contentOf(SailRepository repository) {
        Model content = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.getStatements(null, null, null).forEach(content::add);
        }
        return content;
    }

    private HistoryChange mergeIntoTheDraft() throws Exception {
        Path source = dir.resolve("source.ttl");
        KeepBothPizzaFixture.writeSource(source);
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.KEEP_BOTH);
        service.mergeOntologies("src", source, "proj", null, null, options, USER, "real@x.org", true);
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(historyService).recordEdit(eq("proj"), eq(USER), eq("real@x.org"), eq("ontologyMerge"), anyString(),
                eq("Ontology merge"), any(), any(), anyString(), any(), any(), eq(true), origin.capture());
        assertTrue(origin.getValue().exact() != null, "the draft merge recorded no exact change");
        Map<String, Object> stamped = new HashMap<>();
        origin.getValue().exact().stamp(stamped, "e1");
        HistoryChange change = new HistoryChange();
        change.setId("m1");
        change.setOperationType("ontologyMerge");
        change.setEntityIRI(OntologyMergeService.MERGE_IRI_PREFIX + "x");
        change.setDraft(true);
        change.setUserId(USER);
        change.setExactDeleted((String) stamped.get("exactDeleted"));
        change.setExactInserted((String) stamped.get("exactInserted"));
        return change;
    }

    private void readAsTheDrafter() {
        SparqlQueryContext.setUserId(USER);
        SparqlQueryContext.setWantsDraft(true);
        when(datasetService.hasActiveDraftOverlay("proj", USER)).thenReturn(true);
    }

    private OntologyQueryService queries() {
        return new OntologyQueryService(datasetService, topLevelCache);
    }

    private static Map<String, String> shown(List<OntologyDto.TreeNode> nodes) {
        return nodes.stream().collect(Collectors.toMap(OntologyDto.TreeNode::getId, OntologyDto.TreeNode::getLabel));
    }

    @Test
    void aParentlessCopyIsATopLevelClassInTheDraftTree() throws Exception {
        mergeIntoTheDraft();
        readAsTheDrafter();

        Map<String, String> roots = shown(queries().topLevelClasses("proj", 5000));

        assertEquals("entity_imported", roots.get(ENTITY + SUFFIX));
        assertEquals("Cheese_imported", roots.get(P + "Cheese" + SUFFIX));
        assertEquals("Pizza_imported", roots.get(P + "Pizza" + SUFFIX));
        assertEquals("Mystery_imported", roots.get(P + "Mystery" + SUFFIX));
        assertEquals("entity", roots.get(ENTITY));
        assertFalse(roots.containsKey(P + "PizzaCheese" + SUFFIX));
        assertFalse(roots.containsKey(P + "PizzaTopping"));
    }

    @Test
    void copiesAndNewItemsShowUnderTheirRenamedParentsInTheDraftTree() throws Exception {
        mergeIntoTheDraft();
        readAsTheDrafter();
        OntologyQueryService queries = queries();

        assertEquals(Map.of(P + "PizzaCheese" + SUFFIX, "Pizza cheese_imported"),
                shown(queries.children("proj", P + "Pizza" + SUFFIX, 1000, 0)));
        assertEquals(Map.of(P + "PizzaCheese", "Cheese"), shown(queries.children("proj", P + "Pizza", 1000, 0)));
        assertEquals(Set.of(P + "PizzaTopping"), shown(queries.children("proj", ENTITY + SUFFIX, 1000, 0)).keySet());
        assertEquals(Set.of(P + "Mozzarella"), shown(queries.children("proj", P + "Mystery" + SUFFIX, 1000, 0)).keySet());
        assertTrue(queries.children("proj", ENTITY, 1000, 0).isEmpty());
    }

    @Test
    void aDraftKeepBothMergeUndoesAndRedoesExactly() throws Exception {
        Model original = contentOf(draftGraph);
        HistoryChange change = mergeIntoTheDraft();
        Model merged = contentOf(draftGraph);
        assertFalse(Models.isomorphic(original, merged));

        RollbackMutationPlanner planner = new RollbackMutationPlanner();
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            for (String update : planner.planUndo(change, List.of()).rawUpdates()) {
                connection.prepareUpdate(update).execute();
            }
        }
        assertTrue(Models.isomorphic(contentOf(draftGraph), original), "undo did not return the draft to its starting state");
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            for (String update : planner.planRedo(change, List.of()).rawUpdates()) {
                connection.prepareUpdate(update).execute();
            }
        }
        assertTrue(Models.isomorphic(contentOf(draftGraph), merged), "redo did not bring the merge back");
    }

    @Test
    void aTopLevelListThatSkippedTheParentlessClassScanIsNotKeptForLaterLoads() throws Exception {
        mergeIntoTheDraft();
        try (RepositoryConnection connection = draftGraph.getConnection()) {
            connection.prepareUpdate("INSERT DATA { <" + P + "TargetOnly> <http://www.w3.org/2000/01/rdf-schema#subClassOf> "
                    + "<http://www.w3.org/2002/07/owl#Thing> }").execute();
        }
        OntologyQueryService queries = queries();

        slowRootScan.set(true);
        Map<String, String> coldRoots = shown(queries.topLevelClasses("proj", 5000));
        assertTrue(coldRoots.containsKey(P + "TargetOnly"));
        assertFalse(coldRoots.containsKey(ENTITY + SUFFIX));
        verify(topLevelCache, after(1000).never()).put(anyString(), any(), anyInt());

        Map<String, String> warmRoots = shown(queries.topLevelClasses("proj", 5000));
        assertEquals("entity_imported", warmRoots.get(ENTITY + SUFFIX));
        verify(topLevelCache, timeout(5000)).put(eq("proj"),
                argThat(nodes -> nodes.stream().anyMatch(node -> node.getId().equals(ENTITY + SUFFIX))), eq(5000));
    }
}
