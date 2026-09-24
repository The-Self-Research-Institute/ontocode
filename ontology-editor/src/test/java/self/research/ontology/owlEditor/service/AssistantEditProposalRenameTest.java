package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import self.research.ontology.owlEditor.dto.ProposeEditRequest;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditGroupInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.CheckResult;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.GroupProposalOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEditProposalRenameTest {

    private static final String TURTLE_DOC = String.join("\n",
            "@prefix : <http://ex.org/pizza#> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            ":Pizza a owl:Class .",
            ":PizzaTopping a owl:Class .",
            ":Margherita rdfs:subClassOf :Pizza ;",
            "    rdfs:comment \"a kind of :Pizza\" .",
            "");

    private static final String RDFXML_DOC = String.join("\n",
            "<?xml version=\"1.0\"?>",
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
            "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"",
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"",
            "     xmlns:pizza=\"http://ex.org/pizza#\">",
            "    <owl:Class rdf:about=\"http://ex.org/pizza#Pizza\"/>",
            "    <pizza:Pizza rdf:about=\"http://ex.org/pizza#mine\"/>",
            "</rdf:RDF>",
            "");

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private AssistantEditGroupRepository groupRepository;

    @Mock
    private StorageManager storageManager;

    @Mock
    private SparqlDatasetService datasetService;

    @TempDir
    Path tempDir;

    private AssistantEditProposalService proposalService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        LineRangeSpliceWriter spliceWriter = new LineRangeSpliceWriter();
        proposalService = new AssistantEditProposalService(sessionService, groupRepository, storageManager,
                new AssistantEditSyntaxValidator(storageManager, spliceWriter),
                new AssistantEditReferenceCoverageValidator(storageManager),
                new AssistantRenameService(storageManager, new AssistantGraphIdentifierLookup(datasetService)));
        ReflectionTestUtils.setField(proposalService, "maxEditBytes", 200000);
        ReflectionTestUtils.setField(proposalService, "maxEditsPerGroup", 2);
        ReflectionTestUtils.setField(proposalService, "maxRenameLines", 5000);
        ReflectionTestUtils.setField(proposalService, "maxGroupsPerRequest", 10);
        ReflectionTestUtils.setField(proposalService, "ttlHours", 24L);
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(session()));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(3L);
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        when(storageManager.extensionFor("rdfxml")).thenReturn("owl");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of("x"), List.of(), false, null));
    }

    @Test
    void derivedTurtleRenamePassesEveryCheckAndPersistsOneEditPerLine() {
        GroupProposalOutcome outcome = proposeOne(renameGroup("turtle", ":Pizza", ":Pie"));

        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        CheckResult renameCheck = check(outcome, "rename_occurrences_complete");
        assertTrue(renameCheck.passed());
        assertTrue(renameCheck.detail().contains("2 occurrences"));
        assertTrue(renameCheck.detail().contains("2 lines"));
        assertTrue(check(outcome, "syntax_valid").passed());
        assertTrue(check(outcome, "complete_reference_coverage").passed());
        assertTrue(check(outcome, "original_text_matches_live").passed());

        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        AssistantEditGroupDocument saved = captor.getValue();
        assertEquals(AssistantEditGroupStatus.PENDING, saved.getStatus());
        assertEquals("turtle", saved.getTargetPath());
        assertEquals(2, saved.getEdits().size());
        assertEquals(3L, saved.getEdits().get(0).getStartLine());
        assertEquals(1, saved.getEdits().get(0).getLineCount());
        assertEquals(":Pizza a owl:Class .", saved.getEdits().get(0).getOriginalText());
        assertEquals(":Pie a owl:Class .", saved.getEdits().get(0).getNewText());
        assertEquals(":Margherita rdfs:subClassOf :Pie ;", saved.getEdits().get(1).getNewText());
        assertEquals(0, saved.getEdits().get(1).getLineDelta());
        assertEquals(2, outcome.getDiff().size());
    }

    @Test
    void derivedRenameUsesItsOwnSizeCapInsteadOfTheHandWrittenEditCap() {
        ReflectionTestUtils.setField(proposalService, "maxEditsPerGroup", 1);

        GroupProposalOutcome outcome = proposeOne(renameGroup("turtle", ":Pizza", ":Pie"));

        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        assertTrue(check(outcome, "size_limits").passed());
    }

    @Test
    void derivedRenameOverTheRenameCapIsRejectedWithoutPartialEdits() {
        ReflectionTestUtils.setField(proposalService, "maxRenameLines", 1);

        GroupProposalOutcome outcome = proposeOne(renameGroup("turtle", ":Pizza", ":Pie"));

        assertFalse(outcome.isValidationPassed());
        assertFalse(check(outcome, "rename_occurrences_complete").passed());
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.VALIDATION_FAILED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getEdits().isEmpty());
        assertTrue(outcome.getDiff().isEmpty());
    }

    @Test
    void derivedRdfXmlRenamePassesSyntaxAndRewritesTheTypedNodeElement() {
        GroupProposalOutcome outcome = proposeOne(renameGroup("rdfxml", "pizza:Pizza", "pizza:Pie"));

        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        assertEquals("    <pizza:Pie rdf:about=\"http://ex.org/pizza#mine\"/>", outcome.getDiff().get(1).after());
    }

    @Test
    void unsupportedFormatRenameIsPersistedAsValidationFailedWithTheSupportedFormatsNamed() {
        GroupProposalOutcome outcome = proposeOne(renameGroup("manchester", "Pizza", "Pie"));

        assertFalse(outcome.isValidationPassed());
        assertEquals(1, outcome.getChecks().size());
        CheckResult renameCheck = check(outcome, "rename_occurrences_complete");
        assertFalse(renameCheck.passed());
        assertTrue(renameCheck.detail().contains("turtle, ntriples and rdfxml"));
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.VALIDATION_FAILED, captor.getValue().getStatus());
        assertEquals("manchester", captor.getValue().getTargetPath());
    }

    @Test
    void groupWithBothEditsAndOperationIsRejected() {
        EditGroupInput group = new EditGroupInput("c1",
                List.of(new EditInput("turtle", new EditRange(3, 1), ":Pizza a owl:Class .", ":Pie a owl:Class .")),
                new EditOperation("rename_identifier", "turtle", ":Pizza", ":Pie"));

        GroupProposalOutcome outcome = proposeOne(group);

        assertFalse(outcome.isValidationPassed());
        assertTrue(check(outcome, "rename_occurrences_complete").detail().contains("not both"));
    }

    @Test
    void handWrittenEditsStillHonourTheirOwnCap() {
        EditGroupInput group = new EditGroupInput("c1", List.of(
                new EditInput("turtle", new EditRange(3, 1), ":Pizza a owl:Class .", ":Pizza a owl:Class ."),
                new EditInput("turtle", new EditRange(4, 1), ":PizzaTopping a owl:Class .", ":PizzaTopping a owl:Class ."),
                new EditInput("turtle", new EditRange(5, 1), "x", "y")));

        GroupProposalOutcome outcome = proposeOne(group);

        assertFalse(check(outcome, "size_limits").passed());
        assertNull(check(outcome, "rename_occurrences_complete"));
    }

    @Test
    void requestJsonWithOperationAndNoEditsDeserializes() throws Exception {
        String json = "{\"groups\":[{\"clientGroupId\":\"c1\",\"operation\":{\"type\":\"rename_identifier\","
                + "\"targetPath\":\"turtle\",\"targetIdentifier\":\":Pizza\",\"replacementIdentifier\":\":Pie\"}},"
                + "{\"clientGroupId\":\"c2\",\"edits\":[{\"targetPath\":\"turtle\",\"range\":{\"startLine\":3,"
                + "\"lineCount\":1},\"originalText\":\"a\",\"newText\":\"b\"}]}]}";

        ProposeEditRequest request = new ObjectMapper().readValue(json, ProposeEditRequest.class);

        assertNull(request.groups().get(0).edits());
        assertEquals(":Pie", request.groups().get(0).operation().replacementIdentifier());
        assertNull(request.groups().get(1).operation());
        assertEquals(3L, request.groups().get(1).edits().get(0).range().startLine());

        List<GroupProposalOutcome> outcomes = proposalService.propose("s1", "u@x.com", request.groups()).getGroups();
        assertTrue(outcomes.get(0).isValidationPassed(), outcomes.get(0).getChecks().toString());
    }

    private GroupProposalOutcome proposeOne(EditGroupInput group) {
        return proposalService.propose("s1", "u@x.com", List.of(group)).getGroups().get(0);
    }

    private EditGroupInput renameGroup(String targetPath, String target, String replacement) {
        return new EditGroupInput("c1", null, new EditOperation("rename_identifier", targetPath, target, replacement));
    }

    private CheckResult check(GroupProposalOutcome outcome, String name) {
        return outcome.getChecks().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private AssistantSessionDocument session() {
        return AssistantSessionDocument.builder()
                .id("s1")
                .projectId("proj-1")
                .userEmail("u@x.com")
                .pinnedRevision(42L)
                .actionType("local-edit")
                .status(AssistantSessionStatus.ACTIVE)
                .build();
    }
}
