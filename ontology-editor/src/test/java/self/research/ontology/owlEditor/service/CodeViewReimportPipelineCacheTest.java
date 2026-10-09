package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeViewReimportPipelineCacheTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI PIZZA = VF.createIRI("http://ex.org/Pizza");
    private static final IRI TOPPING = VF.createIRI("http://ex.org/hasTopping");

    @Mock
    private StorageManager storageManager;
    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;
    @Mock
    private DraftTrackingService draftTrackingService;
    @Mock
    private OntologyMutationService ontologyMutationService;
    @Mock
    private OntologySpringCacheEvictionService springCacheEviction;

    private CodeViewReimportPipeline pipeline;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pipeline = new CodeViewReimportPipeline(storageManager, datasetService, metadataService,
                historyService, draftTrackingService);
        ReflectionTestUtils.setField(pipeline, "ontologyMutationService", ontologyMutationService);
        ReflectionTestUtils.setField(pipeline, "springCacheEviction", springCacheEviction);
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(9L);
    }

    private static Path rdfXmlSnapshot() throws Exception {
        Path file = Files.createTempFile("pipeline-cache-test-", ".owl");
        Files.writeString(file,
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>",
                StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void patchedLabelEditClearsTheCachedDetailsOfTheEditedClass() throws Exception {
        Model removed = new LinkedHashModel();
        removed.add(PIZZA, RDFS.LABEL, VF.createLiteral("Pizza"));
        Model added = new LinkedHashModel();
        added.add(PIZZA, RDFS.LABEL, VF.createLiteral("Pizza Margherita"));
        Path patched = Files.createTempFile("patched-", ".ttl");

        pipeline.finishPatch("proj-1", "turtle", patched, "u1", "User", removed, added);

        verify(ontologyMutationService).invalidateEntityCaches("proj-1",
                List.of(PIZZA.stringValue(), RDFS.LABEL.stringValue()), false);
        verify(springCacheEviction).evictForProject("proj-1");
        verify(storageManager).storeCodeViewCacheFile("proj-1", "turtle", patched);
    }

    @Test
    void aPatchedApplyThatOnlyAddsAPrefixDeclarationIsRecordedInTheHistoryAsAPrefixAddition() throws Exception {
        Model removed = new LinkedHashModel();
        removed.setNamespace("owl", "http://www.w3.org/2002/07/owl#");
        Model added = new LinkedHashModel();
        added.setNamespace("owl", "http://www.w3.org/2002/07/owl#");
        added.setNamespace("pizza2023", "http://ex.org/pizza#");
        Path patched = Files.createTempFile("patched-", ".ttl");

        pipeline.finishPatch("proj-1", "turtle", patched, "u1", "User", removed, added);

        verify(historyService).recordEdit(eq("proj-1"), eq("u1"), eq("User"), eq("prefixAdded"), isNull(),
                eq("pizza2023"), isNull(), eq("http://ex.org/pizza#"), anyString(), isNull(), isNull(),
                eq(false), any());
    }

    @Test
    void aPatchedApplyThatLeavesThePrefixesAloneRecordsNoPrefixChange() throws Exception {
        Model removed = new LinkedHashModel();
        removed.setNamespace("owl", "http://www.w3.org/2002/07/owl#");
        removed.add(PIZZA, RDFS.LABEL, VF.createLiteral("Pizza"));
        Model added = new LinkedHashModel();
        added.setNamespace("owl", "http://www.w3.org/2002/07/owl#");
        added.add(PIZZA, RDFS.LABEL, VF.createLiteral("Pizza Margherita"));
        Path patched = Files.createTempFile("patched-", ".ttl");

        pipeline.finishPatch("proj-1", "turtle", patched, "u1", "User", removed, added);

        verify(historyService, never()).recordEdit(anyString(), anyString(), anyString(), eq("prefixAdded"),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
        verify(historyService, never()).recordEdit(anyString(), anyString(), anyString(), eq("prefixModified"),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void touchedIrisReachTheClassBehindABlankNodeRestriction() {
        var restriction = VF.createBNode();
        Model added = new LinkedHashModel();
        added.add(PIZZA, RDFS.SUBCLASSOF, restriction);
        added.add(restriction, OWL.ONPROPERTY, TOPPING);

        List<String> touched = CodeViewReimportPipeline.touchedIris(new LinkedHashModel(), added);

        assertEquals(List.of(PIZZA.stringValue(), RDFS.SUBCLASSOF.stringValue(),
                OWL.ONPROPERTY.stringValue(), TOPPING.stringValue()), touched);
    }

    @Test
    void restoringTheWholeGraphDropsEveryCachedEntityEntry() throws Exception {
        pipeline.restoreSnapshot("proj-1", rdfXmlSnapshot());

        verify(ontologyMutationService).invalidateEntityCaches(eq("proj-1"), isNull(), eq(false));
        verify(springCacheEviction).evictForProject("proj-1");
    }

    @Test
    void restoringADraftLeavesThePublicCachesAlone() throws Exception {
        when(datasetService.getDraftGraphUri("proj-1", "u1")).thenReturn("urn:draft:proj-1:u1");

        pipeline.restoreSnapshot("proj-1", rdfXmlSnapshot(), true, "u1");

        verify(ontologyMutationService).invalidateEntityCaches(eq("proj-1"), isNull(), eq(true));
        verify(springCacheEviction, never()).evictForProject(anyString());
    }

    @Mock
    private DesktopOntologyLoader desktopOntologyLoader;
    @Mock
    private self.research.ontology.owlEditor.cache.ProjectOntologyCache ontologyCache;

    private void wireDesktop(boolean owlApiFirst) {
        ReflectionTestUtils.setField(pipeline, "desktopOntologyLoader", desktopOntologyLoader);
        ReflectionTestUtils.setField(pipeline, "ontologyCache", ontologyCache);
        when(desktopOntologyLoader.isOwlApiFirst()).thenReturn(owlApiFirst);
    }

    @Test
    void desktopDraftIsRewrittenBeforeTheInMemoryModelIsDroppedAndThenReloaded() throws Exception {
        wireDesktop(true);
        Path patched = Files.createTempFile("patched-", ".ttl");

        pipeline.finishPatch("proj-1", "turtle", patched, "u1", "User", new LinkedHashModel(), new LinkedHashModel());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(desktopOntologyLoader, ontologyCache);
        order.verify(desktopOntologyLoader).refreshDraftFromTripleStore("proj-1");
        order.verify(ontologyCache).evict("proj-1");
        order.verify(desktopOntologyLoader).scheduleRewarm("proj-1");
    }

    @Test
    void draftModeWritesLeaveTheDesktopWorkingCopyAlone() throws Exception {
        wireDesktop(true);
        when(datasetService.getDraftGraphUri("proj-1", "u1")).thenReturn("urn:draft:proj-1:u1");

        pipeline.restoreSnapshot("proj-1", rdfXmlSnapshot(), true, "u1");

        verify(desktopOntologyLoader, never()).refreshDraftFromTripleStore(anyString());
        verify(desktopOntologyLoader, never()).scheduleRewarm(anyString());
    }

    @Test
    void withoutOwlApiFirstTheDesktopWorkingCopyIsNotTouched() throws Exception {
        wireDesktop(false);

        pipeline.restoreSnapshot("proj-1", rdfXmlSnapshot());

        verify(desktopOntologyLoader, never()).refreshDraftFromTripleStore(anyString());
        verify(desktopOntologyLoader, never()).scheduleRewarm(anyString());
    }

    @Test
    void aCacheFailureDoesNotFailAnEditThatAlreadyReachedTheGraph() throws Exception {
        doThrow(new RuntimeException("mongo down"))
                .when(ontologyMutationService).invalidateEntityCaches(anyString(), any(), anyBoolean());
        Path patched = Files.createTempFile("patched-", ".ttl");

        long version = pipeline.finishPatch("proj-1", "turtle", patched, "u1", "User",
                new LinkedHashModel(), new LinkedHashModel());

        assertEquals(9L, version);
        verify(storageManager).storeCodeViewCacheFile("proj-1", "turtle", patched);
    }
}
