package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditGroupInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.GroupProposalOutcome;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.ProposeEditResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

abstract class AssistantEditProposalTestBase {

    @Mock
    AssistantSessionService sessionService;

    @Mock
    AssistantEditGroupRepository groupRepository;

    @Mock
    StorageManager storageManager;

    @Mock
    LineRangeSpliceWriter spliceWriter;

    @Mock
    SparqlDatasetService datasetService;

    AssistantEditSyntaxValidator syntaxValidator;

    AssistantEditReferenceCoverageValidator referenceCoverageValidator;

    @Mock
    AssistantAuditService auditService;

    @Mock
    AssistantConsistencyCheckService consistencyCheckService;

    @TempDir
    Path tempDir;

    FakeAssistantGraph graph;

    AssistantEditProposalService proposalService;

    static final String DEFAULT_DOC = String.join("\n",
            "@prefix : <http://example.org/> . @prefix owl: <http://www.w3.org/2002/07/owl#> . "
                    + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            ":OldClass a owl:Class .",
            ":Other a owl:Class .",
            ":A rdfs:comment \"old text\" .",
            "");

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        syntaxValidator = new AssistantEditSyntaxValidator(storageManager, spliceWriter);
        referenceCoverageValidator = new AssistantEditReferenceCoverageValidator(storageManager);
        AssistantGraphIdentifierLookup lookup = new AssistantGraphIdentifierLookup(datasetService);
        proposalService = new AssistantEditProposalService(sessionService, groupRepository, storageManager,
                syntaxValidator, referenceCoverageValidator, new AssistantRenameService(storageManager, lookup),
                new AssistantSwrlAxiomInsertionService(storageManager, lookup),
                new AssistantFuzzyMembershipInsertionService(storageManager, lookup,
                        new FuzzyMembershipQueryService(datasetService)),
                new AssistantEditSemanticValidator(storageManager, lookup), auditService, new ProjectWriteLockRegistry(),
                new AssistantInsertionSnapper(storageManager), consistencyCheckService);
        graph = FakeAssistantGraph.installOn(datasetService);
        graph.everythingExists = true;
        ReflectionTestUtils.setField(proposalService, "maxEditBytes", 200000);
        ReflectionTestUtils.setField(proposalService, "maxEditsPerGroup", 20);
        ReflectionTestUtils.setField(proposalService, "maxGroupsPerRequest", 10);
        ReflectionTestUtils.setField(proposalService, "ttlHours", 24L);
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(consistencyCheckService.gateCheck(anyString(), anyString(), any())).thenReturn(
                new AssistantEditProposalService.CheckResult(
                        AssistantConsistencyCheckService.CONSISTENCY_PRESERVED_CHECK, true,
                        "Skipped — this edit doesn't appear to touch class axioms."));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(7L);
        when(storageManager.ensureCodeViewFile(anyString(), anyString())).thenReturn(write("default.ttl", DEFAULT_DOC));
        when(storageManager.extensionFor(anyString())).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenAnswer(inv -> validSplicedTurtleFile());
    }

    Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    Path validSplicedTurtleFile() throws Exception {
        Path tempFile = Files.createTempFile("test-splice-", ".ttl");
        Files.writeString(tempFile,
                "@prefix : <http://example.org/> .\n"
                        + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + ":NewClass a owl:Class .",
                StandardCharsets.UTF_8);
        return tempFile;
    }

    void mockLiveContent(String format, long startLine, int lineCount, String content) throws Exception {
        when(storageManager.readCodeViewPage("proj-1", format, startLine, lineCount))
                .thenReturn(new StorageManager.CodeViewPage(content, startLine, lineCount, 10, 100));
    }

    EditGroupInput validGroup() {
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .", ":NewClass a owl:Class .");
        return new EditGroupInput("c1", List.of(edit));
    }

    Optional<AssistantEditProposalService.CheckResult> checkNamed(GroupProposalOutcome outcome, String name) {
        return outcome.getChecks().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    AssistantSessionDocument activeSession() {
        return activeSessionWithActionType("local-edit");
    }

    AssistantSessionDocument activeSessionWithActionType(String actionType) {
        return AssistantSessionDocument.builder()
                .id("s1")
                .projectId("proj-1")
                .userEmail("u@x.com")
                .pinnedRevision(42L)
                .actionType(actionType)
                .status(AssistantSessionStatus.ACTIVE)
                .build();
    }

    AssistantAuditService.AssistantAuditEvent singleAuditEvent() {
        ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> captor =
                ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    AssistantSessionDocument sessionWithProvider() {
        return sessionWithProvider("local-edit");
    }

    AssistantSessionDocument sessionWithProvider(String actionType) {
        AssistantSessionDocument session = activeSessionWithActionType(actionType);
        session.setProvider("anthropic");
        session.setModel("claude-test");
        return session;
    }
}
