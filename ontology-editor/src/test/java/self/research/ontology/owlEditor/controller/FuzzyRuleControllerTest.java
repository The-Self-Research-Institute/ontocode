package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.repository.FuzzyRuleRepository;
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentSnapshots;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RequestActor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FuzzyRuleControllerTest {

    private FuzzyRuleRepository ruleRepository;
    private DocumentHistory documentHistory;
    private DocumentSnapshots snapshots;
    private FuzzyRuleController controller;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(FuzzyRuleRepository.class);
        documentHistory = mock(DocumentHistory.class);
        snapshots = mock(DocumentSnapshots.class);
        controller = new FuzzyRuleController(ruleRepository, documentHistory, snapshots, new ProjectWriteLockRegistry());
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshots.snapshot(any())).thenAnswer(invocation -> {
            FuzzyRuleEntity rule = invocation.getArgument(0);
            return rule == null ? null : "{\"name\":\"" + rule.getName() + "\",\"condition\":\"" + rule.getCondition() + "\"}";
        });
    }

    private static FuzzyRuleEntity existing() {
        FuzzyRuleEntity existing = new FuzzyRuleEntity();
        existing.setId("rule-1");
        existing.setProjectId("proj-1");
        existing.setName("Rule1");
        existing.setCondition("A");
        existing.setAction("B");
        return existing;
    }

    @Test
    void createRuleRecordsAHistoryEntryWithTheNewDocument() {
        FuzzyRuleController.FuzzyRuleRequest request =
                new FuzzyRuleController.FuzzyRuleRequest("Rule1", "A", "B", true);

        ResponseEntity<FuzzyRuleEntity> response = controller.createRule("proj-1", request, "u1", "User One", null);

        FuzzyRuleEntity saved = response.getBody();
        ArgumentCaptor<RequestActor> actor = ArgumentCaptor.forClass(RequestActor.class);
        verify(documentHistory).record(eq("proj-1"), actor.capture(), eq(DocumentStore.FUZZY_RULE), isNull(),
                eq("{\"name\":\"Rule1\",\"condition\":\"A\"}"), eq("createFuzzyRule"),
                eq("urn:fuzzyrule:" + saved.getId()), eq("Rule1"), isNull(), eq("IF A THEN B"),
                eq("Created fuzzy rule: Rule1"));
        assertEquals("u1", actor.getValue().userId());
    }

    @Test
    void updateRuleKeepsTheDocumentAsItWasBeforeMutating() {
        when(ruleRepository.findById("rule-1")).thenReturn(Optional.of(existing()));
        FuzzyRuleController.FuzzyRuleRequest request = new FuzzyRuleController.FuzzyRuleRequest(null, "C", null, null);

        controller.updateRule("proj-1", "rule-1", request, "u1", "User One", null);

        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.FUZZY_RULE),
                eq("{\"name\":\"Rule1\",\"condition\":\"A\"}"), eq("{\"name\":\"Rule1\",\"condition\":\"C\"}"),
                eq("updateFuzzyRule"), eq("urn:fuzzyrule:rule-1"), eq("Rule1"), eq("IF A THEN B"), eq("IF C THEN B"),
                eq("Updated fuzzy rule: Rule1"));
    }

    @Test
    void deleteRuleKeepsTheDeletedDocument() {
        when(ruleRepository.findById("rule-1")).thenReturn(Optional.of(existing()));

        ResponseEntity<?> response = controller.deleteRule("proj-1", "rule-1", "u1", "User One", null);

        assertEquals(200, response.getStatusCode().value());
        verify(ruleRepository).deleteById("rule-1");
        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.FUZZY_RULE),
                eq("{\"name\":\"Rule1\",\"condition\":\"A\"}"), isNull(), eq("deleteFuzzyRule"),
                eq("urn:fuzzyrule:rule-1"), eq("Rule1"), eq("IF A THEN B"), isNull(), eq("Deleted fuzzy rule: Rule1"));
    }

    @Test
    void deleteRuleReturnsNotFoundWhenRuleIsAbsent() {
        when(ruleRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteRule("proj-1", "missing", "u1", "User One", null);

        assertEquals(404, response.getStatusCode().value());
        verify(documentHistory, never()).record(anyString(), any(), any(), any(), any(), anyString(), anyString(), any(),
                any(), any(), anyString());
    }
}
