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

class AssistantEditProposalServiceTest {

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private AssistantEditGroupRepository groupRepository;

    @Mock
    private StorageManager storageManager;

    @Mock
    private LineRangeSpliceWriter spliceWriter;

    @Mock
    private SparqlDatasetService datasetService;

    private AssistantEditSyntaxValidator syntaxValidator;

    private AssistantEditReferenceCoverageValidator referenceCoverageValidator;

    @Mock
    private AssistantAuditService auditService;

    @TempDir
    Path tempDir;

    private FakeAssistantGraph graph;

    private AssistantEditProposalService proposalService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        syntaxValidator = new AssistantEditSyntaxValidator(storageManager, spliceWriter);
        referenceCoverageValidator = new AssistantEditReferenceCoverageValidator(storageManager);
        AssistantGraphIdentifierLookup lookup = new AssistantGraphIdentifierLookup(datasetService);
        proposalService = new AssistantEditProposalService(sessionService, groupRepository, storageManager,
                syntaxValidator, referenceCoverageValidator, new AssistantRenameService(storageManager, lookup),
                new AssistantEditSemanticValidator(storageManager, lookup), auditService);
        graph = FakeAssistantGraph.installOn(datasetService);
        graph.everythingExists = true;
        ReflectionTestUtils.setField(proposalService, "maxEditBytes", 200000);
        ReflectionTestUtils.setField(proposalService, "maxEditsPerGroup", 20);
        ReflectionTestUtils.setField(proposalService, "maxGroupsPerRequest", 10);
        ReflectionTestUtils.setField(proposalService, "ttlHours", 24L);
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(7L);
        when(storageManager.ensureCodeViewFile(anyString(), anyString())).thenReturn(write("default.ttl", DEFAULT_DOC));
        when(storageManager.extensionFor(anyString())).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenAnswer(inv -> validSplicedTurtleFile());
    }

    private static final String DEFAULT_DOC = String.join("\n",
            "@prefix : <http://example.org/> . @prefix owl: <http://www.w3.org/2002/07/owl#> . "
                    + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            ":OldClass a owl:Class .",
            ":Other a owl:Class .",
            ":A rdfs:comment \"old text\" .",
            "");

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path validSplicedTurtleFile() throws Exception {
        Path tempFile = Files.createTempFile("test-splice-", ".ttl");
        Files.writeString(tempFile,
                "@prefix : <http://example.org/> .\n"
                        + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + ":NewClass a owl:Class .",
                StandardCharsets.UTF_8);
        return tempFile;
    }

    @Test
    void sessionNotFoundReturnsErrorEnvelope() {
        when(sessionService.getActiveSession("s2", "u@x.com")).thenReturn(Optional.empty());

        ProposeEditResult result = proposalService.propose("s2", "u@x.com", List.of(validGroup()));

        assertFalse(result.isOk());
        assertEquals("SESSION_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void tooManyGroupsRejectedAtRequestLevel() {
        ReflectionTestUtils.setField(proposalService, "maxGroupsPerRequest", 1);

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(validGroup(), validGroup()));

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
    }

    @Test
    void emptyEditsGroupFailsHasEditsCheck() throws Exception {
        mockLiveContent("turtle", 1, 2, ":A a owl:Class .");
        EditGroupInput group = new EditGroupInput("c1", List.of());

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertTrue(checkNamed(outcome, "has_edits").isPresent());
        assertFalse(checkNamed(outcome, "has_edits").get().passed());
    }

    @Test
    void mixedTargetPathsFailSingleTargetPathCheck() {
        EditInput turtleEdit = new EditInput("turtle", new EditRange(1, 1), "old", "new");
        EditInput rdfxmlEdit = new EditInput("rdfxml", new EditRange(5, 1), "old2", "new2");
        EditGroupInput group = new EditGroupInput("c1", List.of(turtleEdit, rdfxmlEdit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "single_target_path").get().passed());
    }

    @Test
    void negativeStartLineFailsRangeWellFormed() {
        EditInput edit = new EditInput("turtle", new EditRange(-1, 1), "old", "new");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(checkNamed(outcome, "range_well_formed").get().passed());
    }

    @Test
    void nullRangeFailsGracefullyInsteadOfThrowing() {
        EditInput edit = new EditInput("turtle", null, "old", "new");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        assertTrue(result.isOk());
        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "range_well_formed").get().passed());
    }

    @Test
    void nullRangeInMultiEditGroupDoesNotCrashOverlapCheck() {
        EditInput withRange = new EditInput("turtle", new EditRange(1, 1), ":A a owl:Class .", ":B a owl:Class .");
        EditInput withoutRange = new EditInput("turtle", null, "old", "new");
        EditGroupInput group = new EditGroupInput("c1", List.of(withRange, withoutRange));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        assertTrue(result.isOk());
        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
    }

    @Test
    void zeroLineCountWithNonEmptyOriginalTextFailsRangeWellFormed() {
        EditInput edit = new EditInput("turtle", new EditRange(3, 0), "not empty", "new");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(checkNamed(outcome, "range_well_formed").get().passed());
    }

    @Test
    void zeroLineCountPureInsertionSkipsLiveMatchReadAndPasses() {
        EditInput edit = new EditInput("turtle", new EditRange(3, 0), "", "inserted line");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(outcome.isValidationPassed());
    }

    @Test
    void overlappingEditsInSameGroupFailOverlapCheck() {
        EditInput a = new EditInput("turtle", new EditRange(0, 3), "a\nb\nc", "x");
        EditInput b = new EditInput("turtle", new EditRange(2, 2), "c\nd", "y");
        EditGroupInput group = new EditGroupInput("c1", List.of(a, b));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(checkNamed(outcome, "no_intra_group_overlap").get().passed());
    }

    @Test
    void adjacentNonOverlappingEditsPassOverlapCheck() throws Exception {
        when(storageManager.readCodeViewPage("proj-1", "turtle", 0, 1))
                .thenReturn(new StorageManager.CodeViewPage("a", 0, 1, 10, 100));
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("b", 1, 1, 10, 100));
        EditInput a = new EditInput("turtle", new EditRange(0, 1), "a", "x");
        EditInput b = new EditInput("turtle", new EditRange(1, 1), "b", "y");
        EditGroupInput group = new EditGroupInput("c1", List.of(a, b));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(checkNamed(outcome, "no_intra_group_overlap").get().passed());
    }

    @Test
    void tooManyEditsInGroupFailsSizeLimits() {
        ReflectionTestUtils.setField(proposalService, "maxEditsPerGroup", 1);
        EditInput a = new EditInput("turtle", new EditRange(0, 1), "a", "x");
        EditInput b = new EditInput("turtle", new EditRange(5, 1), "b", "y");
        EditGroupInput group = new EditGroupInput("c1", List.of(a, b));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(checkNamed(outcome, "size_limits").get().passed());
    }

    @Test
    void oversizedNewTextFailsSizeLimits() {
        ReflectionTestUtils.setField(proposalService, "maxEditBytes", 5);
        EditInput edit = new EditInput("turtle", new EditRange(0, 1), "a", "this is way too long");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(checkNamed(outcome, "size_limits").get().passed());
    }

    @Test
    void liveContentMismatchFailsValidation() throws Exception {
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("actually different", 1, 1, 10, 100));
        EditGroupInput group = validGroup();

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "original_text_matches_live").get().passed());
    }

    @Test
    void liveContentMatchPassesValidationAndPersistsPendingGroup() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        EditGroupInput group = validGroup();

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        assertTrue(result.isOk());
        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(outcome.isValidationPassed());
        assertEquals(1, outcome.getDiff().size());
        assertEquals(":OldClass a owl:Class .", outcome.getDiff().get(0).before());

        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.PENDING, captor.getValue().getStatus());
        assertEquals("proj-1", captor.getValue().getProjectId());
        assertEquals(7L, captor.getValue().getPublicGraphVersionAtPropose());
    }

    @Test
    void validationFailedGroupIsStillPersisted() throws Exception {
        when(storageManager.readCodeViewPage(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(new StorageManager.CodeViewPage("mismatch", 1, 1, 10, 100));
        EditGroupInput group = validGroup();

        proposalService.propose("s1", "u@x.com", List.of(group));

        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.VALIDATION_FAILED, captor.getValue().getStatus());
    }

    private void mockLiveContent(String format, long startLine, int lineCount, String content) throws Exception {
        when(storageManager.readCodeViewPage("proj-1", format, startLine, lineCount))
                .thenReturn(new StorageManager.CodeViewPage(content, startLine, lineCount, 10, 100));
    }

    private EditGroupInput validGroup() {
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .", ":NewClass a owl:Class .");
        return new EditGroupInput("c1", List.of(edit));
    }

    private Optional<AssistantEditProposalService.CheckResult> checkNamed(GroupProposalOutcome outcome, String name) {
        return outcome.getChecks().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private AssistantSessionDocument activeSession() {
        return activeSessionWithActionType("local-edit");
    }

    private AssistantSessionDocument activeSessionWithActionType(String actionType) {
        return AssistantSessionDocument.builder()
                .id("s1")
                .projectId("proj-1")
                .userEmail("u@x.com")
                .pinnedRevision(42L)
                .actionType(actionType)
                .status(AssistantSessionStatus.ACTIVE)
                .build();
    }

    @Test
    void askActionTypeRejectedAtRequestLevel() {
        when(sessionService.getActiveSession("s1", "u@x.com"))
                .thenReturn(Optional.of(activeSessionWithActionType("ask")));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(validGroup()));

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void projectFindingsActionTypeRejectedAtRequestLevel() {
        when(sessionService.getActiveSession("s1", "u@x.com"))
                .thenReturn(Optional.of(activeSessionWithActionType("project-findings")));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(validGroup()));

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void invalidSyntaxAfterSpliceFailsValidation() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        Path brokenFile = Files.createTempFile("test-splice-broken-", ".ttl");
        Files.writeString(brokenFile, "this is not valid turtle @@@ <<<", StandardCharsets.UTF_8);
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(brokenFile);

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(validGroup()));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "syntax_valid").get().passed());
    }

    @Test
    void validSyntaxAfterSplicePassesSyntaxCheck() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(validGroup()));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(checkNamed(outcome, "syntax_valid").get().passed());
    }

    @Test
    void owlApiFormatSkipsSyntaxCheckEntirelyAtProposeTime() throws Exception {
        EditInput edit = new EditInput("functional", new EditRange(1, 1), "Old", "New");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));
        when(storageManager.readCodeViewPage("proj-1", "functional", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("Old", 1, 1, 10, 100));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(checkNamed(outcome, "syntax_valid").get().passed());
        verify(spliceWriter, never()).splice(any(), anyString(), any());
    }

    @Test
    void incompleteRenameLeavingOtherReferencesFailsCoverageCheck() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        Path fullDocument = Files.createTempFile("proposal-fulldoc-", ".ttl");
        Files.writeString(fullDocument,
                "@prefix : <http://example.org/> .\n"
                        + ":OldClass a owl:Class .\n"
                        + ":Something rdfs:subClassOf :OldClass .\n",
                StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(fullDocument);
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .", ":NewClass a owl:Class .");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "complete_reference_coverage").get().passed());
        assertTrue(checkNamed(outcome, "complete_reference_coverage").get().detail().contains(":OldClass"));
    }

    @Test
    void renameCoveringEveryOccurrencePassesCoverageCheck() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        Path fullDocument = Files.createTempFile("proposal-fulldoc-", ".ttl");
        Files.writeString(fullDocument,
                "@prefix : <http://example.org/> .\n"
                        + ":OldClass a owl:Class .\n"
                        + ":Something rdfs:comment \"unrelated\" .\n",
                StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(fullDocument);
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .", ":NewClass a owl:Class .");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(checkNamed(outcome, "complete_reference_coverage").get().passed());
    }

    @Test
    void coverageMatchesWholeTokensAndIgnoresStringLiteralsInTurtle() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        Path fullDocument = Files.createTempFile("proposal-fulldoc-", ".ttl");
        Files.writeString(fullDocument,
                "@prefix : <http://example.org/> .\n"
                        + ":OldClass a owl:Class .\n"
                        + ":OldClassExtra a owl:Class .\n"
                        + ":Other rdfs:comment \"\"\"mentions\n:OldClass in a long string\"\"\" .\n",
                StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(fullDocument);
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .", ":NewClass a owl:Class .");

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(new EditGroupInput("c1", List.of(edit))));

        assertTrue(checkNamed(result.getGroups().get(0), "complete_reference_coverage").get().passed());
    }

    @Test
    void editThatDoesNotRemoveAnyIdentifierSkipsCoverageScanEntirely() throws Exception {
        mockLiveContent("turtle", 1, 1, ":A rdfs:comment \"old text\" .");
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":A rdfs:comment \"old text\" .", ":A rdfs:comment \"new text\" .");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(outcome.isValidationPassed());
        assertTrue(checkNamed(outcome, "complete_reference_coverage").get().passed());
    }

    @Test
    void movingOneEntityToADifferentRelationLeavesUnrelatedEntitiesUnflagged() throws Exception {
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("ex:Alice ex:memberOf ex:TeamA .", 1, 1, 10, 100));
        Path fullDocument = Files.createTempFile("proposal-fulldoc-", ".ttl");
        Files.writeString(fullDocument,
                "@prefix ex: <http://ex.org/> .\n"
                        + "ex:Alice ex:memberOf ex:TeamA .\n"
                        + "ex:Bob ex:memberOf ex:TeamA .\n",
                StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(fullDocument);
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), "ex:Alice ex:memberOf ex:TeamA .", "ex:Alice ex:memberOf ex:TeamB .");
        EditGroupInput group = new EditGroupInput("c1", List.of(edit));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(outcome.isValidationPassed());
        assertTrue(checkNamed(outcome, "complete_reference_coverage").get().passed());
    }

    @Test
    void renamingAndReusingTheOldTokenElsewhereInTheSameGroupIsNotFlagged() throws Exception {
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage(":OldClass a owl:Class .", 1, 1, 10, 100));
        when(storageManager.readCodeViewPage("proj-1", "turtle", 10, 1))
                .thenReturn(new StorageManager.CodeViewPage(":Other rdfs:comment \"mentions :OldClass\" .", 10, 1, 10, 100));
        EditInput rename = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .", ":NewClass a owl:Class .");
        EditInput keepsOldToken = new EditInput("turtle", new EditRange(10, 1),
                ":Other rdfs:comment \"mentions :OldClass\" .", ":Other rdfs:comment \"still mentions :OldClass\" .");
        EditGroupInput group = new EditGroupInput("c1", List.of(rename, keepsOldToken));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(checkNamed(outcome, "complete_reference_coverage").get().passed());
    }

    @Test
    void structurallyUnsoundGroupNeverReachesSpliceWriter() throws Exception {
        EditInput turtleEdit = new EditInput("turtle", new EditRange(1, 1), "old", "new");
        EditInput rdfxmlEdit = new EditInput("rdfxml", new EditRange(5, 1), "old2", "new2");
        EditGroupInput group = new EditGroupInput("c1", List.of(turtleEdit, rdfxmlEdit));

        proposalService.propose("s1", "u@x.com", List.of(group));

        verify(spliceWriter, never()).splice(any(), anyString(), any());
    }

    @Test
    void referenceToAnIdentifierMissingFromTheGraphFailsReferencesResolve() throws Exception {
        graph.everythingExists = false;
        graph.existing.add("http://example.org/OldClass");
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        EditInput edit = new EditInput("turtle", new EditRange(1, 1), ":OldClass a owl:Class .",
                ":OldClass a owl:Class ; rdfs:subClassOf :Pizzza .");

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com",
                List.of(new EditGroupInput("c1", List.of(edit)))).getGroups().get(0);

        assertFalse(outcome.isValidationPassed());
        AssistantEditProposalService.CheckResult references = checkNamed(outcome, "references_resolve").get();
        assertFalse(references.passed());
        assertTrue(references.detail().contains("<http://example.org/Pizzza>"), references.detail());
        assertTrue(checkNamed(outcome, "no_conflicting_declaration").get().passed());
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.VALIDATION_FAILED, captor.getValue().getStatus());
    }

    @Test
    void declaringAClassAsAPropertyFailsNoConflictingDeclaration() throws Exception {
        graph.declare("http://example.org/A", AssistantGraphIdentifierLookup.OWL_CLASS);
        mockLiveContent("turtle", 3, 1, ":A rdfs:comment \"old text\" .");
        EditInput edit = new EditInput("turtle", new EditRange(3, 1), ":A rdfs:comment \"old text\" .",
                ":A a owl:ObjectProperty ; rdfs:comment \"old text\" .");

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com",
                List.of(new EditGroupInput("c1", List.of(edit)))).getGroups().get(0);

        assertFalse(outcome.isValidationPassed());
        AssistantEditProposalService.CheckResult conflict = checkNamed(outcome, "no_conflicting_declaration").get();
        assertFalse(conflict.passed());
        assertTrue(conflict.detail().contains("owl:ObjectProperty"), conflict.detail());
        assertTrue(conflict.detail().contains("owl:Class"), conflict.detail());
    }

    @Test
    void semanticChecksAreReportedAsPassedButNotApplicableForOwlApiFormats() throws Exception {
        EditInput edit = new EditInput("manchester", new EditRange(1, 1), "Old", "New");
        when(storageManager.readCodeViewPage("proj-1", "manchester", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("Old", 1, 1, 10, 100));

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com",
                List.of(new EditGroupInput("c1", List.of(edit)))).getGroups().get(0);

        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        AssistantEditProposalService.CheckResult references = checkNamed(outcome, "references_resolve").get();
        assertTrue(references.passed());
        assertTrue(references.detail().startsWith("Not applicable"), references.detail());
        assertTrue(references.detail().contains("manchester"));
        assertTrue(graph.queries.isEmpty());
    }

    @Test
    void structurallyUnsoundGroupSkipsSemanticChecksWithoutQueryingTheGraph() {
        EditInput edit = new EditInput("turtle", new EditRange(-1, 1), "old", "new");

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com",
                List.of(new EditGroupInput("c1", List.of(edit)))).getGroups().get(0);

        assertTrue(checkNamed(outcome, "references_resolve").get().detail().startsWith("Skipped"));
        assertTrue(checkNamed(outcome, "no_conflicting_declaration").get().detail().startsWith("Skipped"));
        assertTrue(graph.queries.isEmpty());
    }

    @Test
    void syntaxFailureCarriesTheParserMessageAndSkipsSemanticChecks() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        Path brokenFile = Files.createTempFile("test-splice-broken-", ".ttl");
        Files.writeString(brokenFile, "@prefix : <http://example.org/> .\n:A :b", StandardCharsets.UTF_8);
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(brokenFile);

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com", List.of(validGroup())).getGroups().get(0);

        AssistantEditProposalService.CheckResult syntax = checkNamed(outcome, "syntax_valid").get();
        assertFalse(syntax.passed());
        assertTrue(syntax.detail().contains("would not parse as turtle"), syntax.detail());
        assertTrue(checkNamed(outcome, "references_resolve").get().detail().contains("does not parse"));
    }

    @Test
    void coverageCheckFailsClosedWhenTheDocumentCannotBeScanned() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(tempDir.resolve("missing.ttl"));

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com", List.of(validGroup())).getGroups().get(0);

        assertFalse(outcome.isValidationPassed());
        AssistantEditProposalService.CheckResult coverage = checkNamed(outcome, "complete_reference_coverage").get();
        assertFalse(coverage.passed());
        assertTrue(coverage.detail().contains(":OldClass"), coverage.detail());
    }

    @Test
    void nullGroupsListIsRejectedAtRequestLevel() {
        ProposeEditResult result = proposalService.propose("s1", "u@x.com", null);

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void nullGroupInsideTheListIsRejectedAtRequestLevel() {
        java.util.ArrayList<EditGroupInput> groups = new java.util.ArrayList<>();
        groups.add(validGroup());
        groups.add(null);

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", groups);

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void nullEditInsideAGroupFailsTheGroupInsteadOfThrowing() {
        java.util.ArrayList<EditInput> edits = new java.util.ArrayList<>();
        edits.add(null);

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(new EditGroupInput("c1", edits)));

        assertTrue(result.isOk());
        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "range_well_formed").get().passed());
    }

    @Test
    void editWithoutATargetPathFailsSingleTargetPath() throws Exception {
        EditInput edit = new EditInput(null, new EditRange(1, 1), "old", "new");

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com",
                List.of(new EditGroupInput("c1", List.of(edit)))).getGroups().get(0);

        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "single_target_path").get().passed());
        verify(spliceWriter, never()).splice(any(), any(), any());
    }

    @Test
    void passedGroupIsAuditedWithTheSessionsProviderAndModel() throws Exception {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(sessionWithProvider()));
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");

        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com", List.of(validGroup())).getGroups().get(0);

        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        AssistantAuditService.AssistantAuditEvent event = singleAuditEvent();
        assertEquals("u@x.com", event.actor());
        assertEquals("proj-1", event.projectId());
        assertEquals("s1", event.sessionId());
        assertEquals(outcome.getServerGroupId(), event.groupId());
        assertEquals(AssistantEditProposalService.PROPOSE_OPERATION, event.operation());
        assertEquals(7L, event.sourceRevision());
        assertEquals("anthropic", event.provider());
        assertEquals("claude-test", event.model());
        assertEquals("pending", event.outcome());
        assertEquals(null, event.errorCode());
        assertTrue(event.detail().contains("1 edit on turtle"), event.detail());
    }

    @Test
    void failedGroupIsAuditedWithTheFailedCheckNames() throws Exception {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(sessionWithProvider()));
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("different", 1, 1, 10, 100));

        proposalService.propose("s1", "u@x.com", List.of(validGroup()));

        AssistantAuditService.AssistantAuditEvent event = singleAuditEvent();
        assertEquals("validation_failed", event.outcome());
        assertEquals("VALIDATION_FAILED", event.errorCode());
        assertTrue(event.detail().contains("original_text_matches_live"), event.detail());
        assertEquals("anthropic", event.provider());
    }

    @Test
    void requestLevelRejectionsAreAudited() {
        when(sessionService.getActiveSession("s2", "u@x.com")).thenReturn(Optional.empty());
        proposalService.propose("s2", "u@x.com", List.of(validGroup()));
        AssistantAuditService.AssistantAuditEvent missing = singleAuditEvent();
        assertEquals("rejected", missing.outcome());
        assertEquals("SESSION_NOT_FOUND", missing.errorCode());
        assertEquals("s2", missing.sessionId());

        org.mockito.Mockito.reset(auditService);
        when(sessionService.getActiveSession("s1", "u@x.com"))
                .thenReturn(Optional.of(sessionWithProvider("ask")));
        proposalService.propose("s1", "u@x.com", List.of(validGroup()));
        AssistantAuditService.AssistantAuditEvent wrongType = singleAuditEvent();
        assertEquals("rejected", wrongType.outcome());
        assertEquals("VALIDATION_FAILED", wrongType.errorCode());
        assertEquals("anthropic", wrongType.provider());
        assertEquals("claude-test", wrongType.model());
    }

    @Test
    void auditFailureDoesNotBreakThePropose() throws Exception {
        mockLiveContent("turtle", 1, 1, ":OldClass a owl:Class .");
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down")).when(auditService).record(any());

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(validGroup()));

        assertTrue(result.isOk());
        assertTrue(result.getGroups().get(0).isValidationPassed());
    }

    private AssistantAuditService.AssistantAuditEvent singleAuditEvent() {
        ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> captor =
                ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    private AssistantSessionDocument sessionWithProvider() {
        return sessionWithProvider("local-edit");
    }

    private AssistantSessionDocument sessionWithProvider(String actionType) {
        AssistantSessionDocument session = activeSessionWithActionType(actionType);
        session.setProvider("anthropic");
        session.setModel("claude-test");
        return session;
    }
}
