package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private RestTemplate restTemplate;

    private AssistantConsistencyCheckService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AssistantConsistencyCheckService(datasetService, storageManager, spliceWriter,
                reimportPipeline, groupRepository, messagingTemplate);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "pluginServiceUrl", "http://localhost:8087");
        ReflectionTestUtils.setField(service, "maxDatasetSizeForCheck", 150000L);
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
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pendingGroup()));

        ReflectionTestUtils.invokeMethod(service, "runCheck", "g1", "proj-1", "turtle",
                List.of(entry()), StorageManager.ContentScope.publicScope(), "assistant-whatif-g1");

        verify(datasetService).copyMainGraphToDraft("proj-1", "assistant-whatif-g1");
        verify(datasetService).clearDraftGraph("proj-1", "assistant-whatif-g1");
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(ConsistencyCheckState.ERROR, captor.getValue().getConsistencyCheck().getState());
    }

    @Test
    void runCheckResolvesPassedWhenTheReasonerReportsConsistent() throws IOException {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(Path.of("source.ttl"));
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(Path.of("spliced.ttl"));
        when(datasetService.getDraftGraphUri("proj-1", "assistant-whatif-g1")).thenReturn("urn:draft:graph:assistant-whatif-g1");
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("consistent", true)));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pendingGroup()));

        ReflectionTestUtils.invokeMethod(service, "runCheck", "g1", "proj-1", "turtle",
                List.of(entry()), StorageManager.ContentScope.publicScope(), "assistant-whatif-g1");

        verify(datasetService).clearDraftGraph("proj-1", "assistant-whatif-g1");
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(ConsistencyCheckState.PASSED, captor.getValue().getConsistencyCheck().getState());
        verify(messagingTemplate).convertAndSend(eq("/topic/assistant/consistency-check/g1"), any(Object.class));
    }

    @Test
    void runCheckResolvesFailedWhenTheReasonerReportsInconsistent() throws IOException {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(Path.of("source.ttl"));
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(Path.of("spliced.ttl"));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("consistent", false)));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pendingGroup()));

        ReflectionTestUtils.invokeMethod(service, "runCheck", "g1", "proj-1", "turtle",
                List.of(entry()), StorageManager.ContentScope.publicScope(), "assistant-whatif-g1");

        verify(datasetService).clearDraftGraph("proj-1", "assistant-whatif-g1");
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(ConsistencyCheckState.FAILED, captor.getValue().getConsistencyCheck().getState());
    }

    @Test
    void resolveIsANoOpOnceTheCheckIsAlreadyInATerminalState() {
        AssistantEditGroupDocument alreadyResolved = AssistantEditGroupDocument.builder().id("g1")
                .consistencyCheck(AssistantEditGroupDocument.ConsistencyCheckRecord.builder()
                        .state(ConsistencyCheckState.TIMED_OUT).build())
                .build();
        when(groupRepository.findById("g1")).thenReturn(Optional.of(alreadyResolved));

        ReflectionTestUtils.invokeMethod(service, "resolve", "g1", ConsistencyCheckState.PASSED, "arrived too late");

        verify(groupRepository, never()).save(any());
    }
}
