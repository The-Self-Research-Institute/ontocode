package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyMutationServiceTest {

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private OntologyIndexService indexService;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private GraphGeneratingService graphGeneratingService;
    @Mock
    private TopLevelClassCacheService topLevelCacheService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private OntologyHistoryService historyService;
    @Mock
    private DraftCopyService draftCopyService;

    private OntologyMutationService service;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Executor directExecutor = Runnable::run;
        service = new OntologyMutationService(datasetService, indexService, metadataService,
                graphGeneratingService, topLevelCacheService, storageManager, directExecutor);
        ReflectionTestUtils.setField(service, "historyService", historyService);
        ReflectionTestUtils.setField(service, "draftCopyService", draftCopyService);
        when(draftCopyService.isReady(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        doNothing().when(datasetService).execUpdate(anyString(), anyString());
    }

    private static GraphQueryResult fakeResult(List<Statement> statements) {
        Iterator<Statement> it = statements.iterator();
        return new GraphQueryResult() {
            @Override public Map<String, String> getNamespaces() { return Map.of(); }
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public Statement next() { return it.next(); }
            @Override public void remove() { }
            @Override public void close() { }
        };
    }

    @Test
    void applyRawUpdateWithHistoryRecordsTheResultingDiff() {
        var subject = VF.createIRI("http://ex.org/Pizza");
        var predicate = VF.createIRI("http://www.w3.org/2000/01/rdf-schema#label");
        Statement before = VF.createStatement(subject, predicate, VF.createLiteral("Pizza"));
        Statement after = VF.createStatement(subject, predicate, VF.createLiteral("Pizza Margherita"));

        when(datasetService.execConstructAll("proj-1"))
                .thenReturn(fakeResult(List.of(before)))
                .thenReturn(fakeResult(List.of(after)));

        service.applyRawUpdateWithHistory("proj-1", "DELETE {...} INSERT {...} WHERE {...}", false, "u1", "User One");

        ArgumentCaptor<String> opType = ArgumentCaptor.forClass(String.class);
        verify(historyService).recordEdit(anyString(), anyString(), anyString(), opType.capture(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(subChanges -> subChanges != null && !subChanges.isEmpty()),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any());
        assertEquals("addStatement", opType.getValue());
    }

    @Test
    void applyRawUpdateWithHistorySkipsRecordingForDraftUpdates() {
        service.applyRawUpdateWithHistory("proj-1", "INSERT DATA {...}", true, "u1", "User One");

        verify(datasetService, org.mockito.Mockito.never()).execConstructAll(anyString());
    }

    @Test
    void applyRawUpdateInvalidatesTheDraftCodeViewCache() {
        service.applyRawUpdate("proj-1", "INSERT DATA {...}", true, "u1");

        verify(datasetService).execDraftUpdateCopyOnSwitch("proj-1", "u1", "INSERT DATA {...}");
        verify(storageManager).bumpDraftGraphVersion("proj-1", "u1");
    }

    @Test
    void makeSiblingsDisjointInvalidatesTheDraftCodeViewCache() {
        service.makeSiblingsDisjoint("proj-1", List.of("http://ex.org/A", "http://ex.org/B"), true, "u1");

        verify(storageManager).bumpDraftGraphVersion("proj-1", "u1");
    }

    @Test
    void applyRawUpdateWithoutHistoryStillWorksWhenHistoryServiceIsAbsent() {
        ReflectionTestUtils.setField(service, "historyService", null);

        service.applyRawUpdateWithHistory("proj-1", "INSERT DATA {...}", false, "u1", "User One");

        verify(datasetService, org.mockito.Mockito.never()).execConstructAll(anyString());
        verify(datasetService).execUpdate("proj-1", "INSERT DATA {...}");
    }
}
