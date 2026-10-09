package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckState;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.CheckResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConsistencyCheckServiceTest {

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private LineRangeSpliceWriter spliceWriter;
    @Mock
    private CodeViewReimportPipeline reimportPipeline;
    @Mock
    private AssistantEditGroupRepository groupRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private RestTemplate restTemplate;

    private AssistantConsistencyCheckService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AssistantConsistencyCheckService(datasetService, storageManager, spliceWriter,
                reimportPipeline, groupRepository, mongoTemplate, messagingTemplate);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "pluginServiceUrl", "http://localhost:8087");
        ReflectionTestUtils.setField(service, "maxDatasetSizeForCheck", 150000L);
        ReflectionTestUtils.setField(service, "wallClockBudgetMs", 60000L);
        ReflectionTestUtils.setField(service, "debounceEnabled", true);
        ReflectionTestUtils.setField(service, "debounceQuietMs", 600L);
        ReflectionTestUtils.setField(service, "debounceMaxWaitMs", 2500L);
        ReflectionTestUtils.setField(service, "debounceMaxBatchSize", 10);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantEditGroupDocument.class))).thenReturn(pendingGroup());
    }

    private static EditEntry entry() {
        return EditEntry.builder().startLine(0).lineCount(1).originalText("").newText(":A rdfs:subClassOf :B .").build();
    }

    private static AssistantEditGroupDocument pendingGroup() {
        return AssistantEditGroupDocument.builder().id("g1").projectId("proj-1")
                .consistencyCheck(AssistantEditGroupDocument.ConsistencyCheckRecord.builder()
                        .state(ConsistencyCheckState.PENDING).build())
                .build();
    }

    private ConsistencyCheckState capturedState() {
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class),
                eq(AssistantEditGroupDocument.class));
        return (ConsistencyCheckState) updateCaptor.getValue().getUpdateObject()
                .get("$set", Document.class).get("consistencyCheck.state");
    }

    @Test
    void gateCheckSkipsWhenNothingTouchesAxioms() {
        EditInput edit = new EditInput("turtle", new EditRange(0, 1), "", ":A rdfs:label \"Pizza\" .");

        CheckResult result = service.gateCheck("proj-1", "turtle", List.of(edit));

        assertTrue(result.passed());
        assertNull(result.status());
    }

    @Test
    void gateCheckMarksPendingWhenAnAxiomTokenIsPresent() {
        when(datasetService.getDatasetSize("proj-1")).thenReturn(500L);
        EditInput edit = new EditInput("turtle", new EditRange(0, 1), "", ":A rdfs:subClassOf :B .");

        CheckResult result = service.gateCheck("proj-1", "turtle", List.of(edit));

        assertEquals(AssistantConsistencyCheckService.PENDING_STATUS, result.status());
    }

    @Test
    void gateCheckSkipsWhenTheOntologyIsTooLargeForALiveCheck() {
        when(datasetService.getDatasetSize("proj-1")).thenReturn(5_000_000L);
        EditInput edit = new EditInput("turtle", new EditRange(0, 1), "", ":A rdfs:subClassOf :B .");

        CheckResult result = service.gateCheck("proj-1", "turtle", List.of(edit));

        assertTrue(result.passed());
        assertNull(result.status());
    }

    @Test
    void runCheckAlwaysClearsTheScratchGraphEvenWhenReimportPrepFails() throws IOException {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenThrow(new IOException("disk gone"));

        ReflectionTestUtils.invokeMethod(service, "runCheck", "g1", "proj-1", "turtle",
                List.of(entry()), StorageManager.ContentScope.publicScope(), "assistant-whatif-g1");

        verify(datasetService).copyMainGraphToDraft("proj-1", "assistant-whatif-g1");
        verify(datasetService).clearDraftGraph("proj-1", "assistant-whatif-g1");
        assertEquals(ConsistencyCheckState.ERROR, capturedState());
    }

    @Test
    void runCheckResolvesPassedWhenTheReasonerReportsConsistent() throws IOException {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(Path.of("source.ttl"));
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(Path.of("spliced.ttl"));
        when(datasetService.getDraftGraphUri("proj-1", "assistant-whatif-g1")).thenReturn("urn:draft:graph:assistant-whatif-g1");
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("consistent", true)));

        ReflectionTestUtils.invokeMethod(service, "runCheck", "g1", "proj-1", "turtle",
                List.of(entry()), StorageManager.ContentScope.publicScope(), "assistant-whatif-g1");

        verify(datasetService).clearDraftGraph("proj-1", "assistant-whatif-g1");
        assertEquals(ConsistencyCheckState.PASSED, capturedState());
        verify(messagingTemplate).convertAndSend(eq("/topic/assistant/consistency-check/g1"), any(Object.class));
    }

    @Test
    void runCheckResolvesFailedWhenTheReasonerReportsInconsistent() throws IOException {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(Path.of("source.ttl"));
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(Path.of("spliced.ttl"));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("consistent", false)));

        ReflectionTestUtils.invokeMethod(service, "runCheck", "g1", "proj-1", "turtle",
                List.of(entry()), StorageManager.ContentScope.publicScope(), "assistant-whatif-g1");

        verify(datasetService).clearDraftGraph("proj-1", "assistant-whatif-g1");
        assertEquals(ConsistencyCheckState.FAILED, capturedState());
    }

    @Test
    void resolveDoesNotPublishWhenTheConditionalUpdateDidNotMatch() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantEditGroupDocument.class))).thenReturn(null);

        ReflectionTestUtils.invokeMethod(service, "resolve", "g1", ConsistencyCheckState.PASSED, "arrived too late");

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void resolvePublishesWhenTheConditionalUpdateMatches() {
        ReflectionTestUtils.invokeMethod(service, "resolve", "g1", ConsistencyCheckState.PASSED, "all good");

        verify(messagingTemplate).convertAndSend(eq("/topic/assistant/consistency-check/g1"), any(Object.class));
    }

    private static AssistantEditGroupDocument group(String id, String sessionId, long versionAtPropose,
                                                     EditEntry... edits) {
        return AssistantEditGroupDocument.builder()
                .id(id).projectId("proj-1").sessionId(sessionId).targetPath("turtle")
                .publicGraphVersionAtPropose(versionAtPropose)
                .edits(new ArrayList<>(List.of(edits)))
                .consistencyCheck(AssistantEditGroupDocument.ConsistencyCheckRecord.builder()
                        .state(ConsistencyCheckState.PENDING).build())
                .build();
    }

    private static EditEntry entryAt(long startLine, int lineCount, String originalText) {
        return EditEntry.builder().startLine(startLine).lineCount(lineCount).originalText(originalText)
                .newText("new text").build();
    }

    private Path liveFileWithLines(String... lines) throws IOException {
        Path file = Files.createTempFile("consistency-batch-", ".ttl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void debounceDisabledDispatchesImmediatelyWithoutQueueing() throws IOException {
        ReflectionTestUtils.setField(service, "debounceEnabled", false);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenThrow(new IOException("stop here"));

        service.startAsyncCheck("g1", "proj-1", "turtle", List.of(entry()), StorageManager.ContentScope.publicScope());

        Map<?, ?> pendingBatches = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingBatches");
        assertTrue(pendingBatches.isEmpty());
        verify(groupRepository, never()).findById(anyString());
    }

    @Test
    void debounceEnabledQueuesInsteadOfDispatchingImmediately() {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group("g1", "session-1", 5L, entry())));

        service.startAsyncCheck("g1", "proj-1", "turtle", List.of(entry()), StorageManager.ContentScope.publicScope());

        Map<?, ?> pendingBatches = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingBatches");
        assertEquals(1, pendingBatches.size());
    }

    @Test
    void debounceFlushesImmediatelyOnceTheBatchSizeCapIsReached() {
        ReflectionTestUtils.setField(service, "debounceMaxBatchSize", 1);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group("g1", "session-1", 5L, entry())));

        service.startAsyncCheck("g1", "proj-1", "turtle", List.of(entry()), StorageManager.ContentScope.publicScope());

        Map<?, ?> pendingBatches = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingBatches");
        assertTrue(pendingBatches.isEmpty());
    }

    @Test
    void mergeEligibleWhenVersionsMatchNoOverlapAndLiveContentMatches() throws IOException {
        Path file = liveFileWithLines("line0", "line1", "line2");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(file);
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 1, "line0"));
        AssistantEditGroupDocument b = group("g2", "s1", 5L, entryAt(1, 1, "line1"));

        boolean eligible = ReflectionTestUtils.invokeMethod(service, "isMergeEligible",
                "proj-1", StorageManager.ContentScope.publicScope(), List.of(a, b));

        assertTrue(eligible);
    }

    @Test
    void notMergeEligibleWhenAGroupsVersionIsStale() throws IOException {
        Path file = liveFileWithLines("line0", "line1", "line2");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(file);
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 1, "line0"));
        AssistantEditGroupDocument b = group("g2", "s1", 4L, entryAt(1, 1, "line1"));

        boolean eligible = ReflectionTestUtils.invokeMethod(service, "isMergeEligible",
                "proj-1", StorageManager.ContentScope.publicScope(), List.of(a, b));

        assertFalse(eligible);
    }

    @Test
    void notMergeEligibleWhenTwoGroupsEditRangesOverlap() {
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 2, "line0\nline1"));
        AssistantEditGroupDocument b = group("g2", "s1", 5L, entryAt(1, 1, "line1"));

        boolean eligible = ReflectionTestUtils.invokeMethod(service, "isMergeEligible",
                "proj-1", StorageManager.ContentScope.publicScope(), List.of(a, b));

        assertFalse(eligible);
    }

    @Test
    void notMergeEligibleWhenLiveContentNoLongerMatchesOneGroupsOriginalText() throws IOException {
        Path file = liveFileWithLines("line0", "CHANGED", "line2");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(file);
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 1, "line0"));
        AssistantEditGroupDocument b = group("g2", "s1", 5L, entryAt(1, 1, "line1"));

        boolean eligible = ReflectionTestUtils.invokeMethod(service, "isMergeEligible",
                "proj-1", StorageManager.ContentScope.publicScope(), List.of(a, b));

        assertFalse(eligible);
    }

    @Test
    void processBatchMergesEligibleGroupsIntoOneCheckAndResolvesBoth() throws IOException {
        Path file = liveFileWithLines("line0", "line1", "line2");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(file);
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(Path.of("spliced.ttl"));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("consistent", true)));
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 1, "line0"));
        AssistantEditGroupDocument b = group("g2", "s1", 5L, entryAt(1, 1, "line1"));
        when(groupRepository.findAllById(List.of("g1", "g2"))).thenReturn(List.of(a, b));

        ReflectionTestUtils.invokeMethod(service, "processBatch", "proj-1", List.of("g1", "g2"));

        verify(mongoTemplate, timeout(5000).times(2)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantEditGroupDocument.class));
        verify(datasetService, times(1)).copyMainGraphToDraft(eq("proj-1"), anyString());
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void processBatchDispatchesIndividuallyWhenGroupsAreNotMergeEligible() throws IOException {
        Path file = liveFileWithLines("line0", "line1", "line2");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(file);
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(Path.of("spliced.ttl"));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("consistent", true)));
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 1, "line0"));
        AssistantEditGroupDocument b = group("g2", "s1", 4L, entryAt(1, 1, "line1"));
        when(groupRepository.findAllById(List.of("g1", "g2"))).thenReturn(List.of(a, b));

        ReflectionTestUtils.invokeMethod(service, "processBatch", "proj-1", List.of("g1", "g2"));

        verify(mongoTemplate, timeout(5000).times(2)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantEditGroupDocument.class));
        verify(datasetService, times(2)).copyMainGraphToDraft(eq("proj-1"), anyString());
    }

    @Test
    void mergedBatchFailurePartwayThroughResolvesEveryMemberToError() throws IOException {
        Path file = liveFileWithLines("line0", "line1", "line2");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(file);
        when(storageManager.resolveGraphVersion("proj-1", StorageManager.ContentScope.publicScope())).thenReturn(5L);
        when(storageManager.extensionFor("turtle")).thenThrow(new RuntimeException("splice prep failed"));
        AssistantEditGroupDocument a = group("g1", "s1", 5L, entryAt(0, 1, "line0"));
        AssistantEditGroupDocument b = group("g2", "s1", 5L, entryAt(1, 1, "line1"));
        when(groupRepository.findAllById(List.of("g1", "g2"))).thenReturn(List.of(a, b));

        ReflectionTestUtils.invokeMethod(service, "processBatch", "proj-1", List.of("g1", "g2"));

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, timeout(5000).times(2)).findAndModify(any(Query.class), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(AssistantEditGroupDocument.class));
        for (Update update : updateCaptor.getAllValues()) {
            ConsistencyCheckState state = (ConsistencyCheckState) update.getUpdateObject()
                    .get("$set", Document.class).get("consistencyCheck.state");
            assertEquals(ConsistencyCheckState.ERROR, state);
        }
        verify(datasetService).clearDraftGraph(eq("proj-1"), anyString());
    }
}
