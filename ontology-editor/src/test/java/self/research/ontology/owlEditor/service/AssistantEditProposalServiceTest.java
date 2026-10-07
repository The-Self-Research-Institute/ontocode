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
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.InferredAxiomInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.FuzzyMembershipInput;
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

class AssistantEditProposalServiceTest extends AssistantEditProposalTestBase {

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
        AssistantEditProposalService.CheckResult check = checkNamed(outcome, "no_intra_group_overlap").get();
        assertFalse(check.passed());
        assertTrue(check.detail().contains("line 2"));
        assertTrue(check.detail().contains("line 3"));
    }

    @Test
    void insertionLandingInsidePrecedingDeleteIsAutoSnappedPastIt() throws Exception {
        mockLiveContent("turtle", 307, 3, "old\nold\nold");
        EditInput delete = new EditInput("turtle", new EditRange(307, 3), "old\nold\nold", "");
        EditInput insert = new EditInput("turtle", new EditRange(309, 0), "", ":NewClass a owl:Class .");
        EditGroupInput group = new EditGroupInput("c1", List.of(delete, insert));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(checkNamed(outcome, "no_intra_group_overlap").get().passed());
        AssistantEditProposalService.CheckResult note =
                checkNamed(outcome, AssistantEditProposalService.INSERTION_MOVED_PAST_SIBLING_CHECK).get();
        assertTrue(note.passed());
        assertTrue(note.detail().contains("line 310"));
        assertTrue(note.detail().contains("line 311"));
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
    void addInferredAxiomsOperationDispatchesToSwrlInsertionAndPasses() throws Exception {
        ReflectionTestUtils.setField(proposalService, "maxSwrlAxioms", 200);
        mockLiveContent("turtle", 0, 0, "");
        InferredAxiomInput axiom = new InferredAxiomInput("ClassAssertion", "http://example.org/pepperoni",
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://example.org/Topping", null, null, null);
        EditGroupInput group = new EditGroupInput("c1", null,
                new EditOperation("add_inferred_axioms", "turtle", null, null, List.of(axiom)));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        assertTrue(checkNamed(outcome, "swrl_inferred_axioms_resolved").get().passed());
        assertEquals(1, outcome.getDiff().size());
        assertEquals("", outcome.getDiff().get(0).before());
        assertTrue(outcome.getDiff().get(0).after().contains("http://example.org/pepperoni"));
    }

    @Test
    void addInferredAxiomsWithUnsupportedFormatIsRejectedWithoutTouchingRenameCheck() {
        EditGroupInput group = new EditGroupInput("c1", null,
                new EditOperation("add_inferred_axioms", "rdfxml", null, null,
                        List.of(new InferredAxiomInput("ClassAssertion", "http://example.org/pepperoni",
                                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://example.org/Topping",
                                null, null, null))));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "swrl_inferred_axioms_resolved").get().passed());
        assertTrue(checkNamed(outcome, "swrl_inferred_axioms_resolved").get().detail().contains("turtle and ntriples"));
        assertTrue(checkNamed(outcome, "rename_occurrences_complete").isEmpty());
    }

    @Test
    void addFuzzyMembershipOperationDispatchesToFuzzyInsertionAndPasses() throws Exception {
        ReflectionTestUtils.setField(proposalService, "maxFuzzyMemberships", 50);
        mockLiveContent("turtle", 0, 0, "");
        FuzzyMembershipInput membership = new FuzzyMembershipInput("http://example.org/alice",
                "http://example.org/Diabetic", 0.9);
        EditGroupInput group = new EditGroupInput("c1", null,
                new EditOperation("add_fuzzy_membership", "turtle", null, null, null, List.of(membership)));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertTrue(outcome.isValidationPassed(), outcome.getChecks().toString());
        assertTrue(checkNamed(outcome, "fuzzy_memberships_resolved").get().passed());
        assertEquals(1, outcome.getDiff().size());
        assertEquals("", outcome.getDiff().get(0).before());
        assertTrue(outcome.getDiff().get(0).after().contains("http://example.org/alice"));
    }

    @Test
    void addFuzzyMembershipWithOutOfRangeDegreeIsRejected() {
        ReflectionTestUtils.setField(proposalService, "maxFuzzyMemberships", 50);
        EditGroupInput group = new EditGroupInput("c1", null,
                new EditOperation("add_fuzzy_membership", "turtle", null, null, null,
                        List.of(new FuzzyMembershipInput("http://example.org/alice", "http://example.org/Diabetic", 1.5))));

        ProposeEditResult result = proposalService.propose("s1", "u@x.com", List.of(group));

        GroupProposalOutcome outcome = result.getGroups().get(0);
        assertFalse(outcome.isValidationPassed());
        assertFalse(checkNamed(outcome, "fuzzy_memberships_resolved").get().passed());
        assertTrue(checkNamed(outcome, "fuzzy_memberships_resolved").get().detail().contains("not between 0.0 and 1.0"));
    }
}
