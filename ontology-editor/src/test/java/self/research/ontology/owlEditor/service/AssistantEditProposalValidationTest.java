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

class AssistantEditProposalValidationTest extends AssistantEditProposalTestBase {

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
}
