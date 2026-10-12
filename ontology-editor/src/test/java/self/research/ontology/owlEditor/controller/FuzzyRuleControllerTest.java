package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.repository.FuzzyRuleRepository;
import self.research.ontology.owlEditor.service.OntologyHistoryService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FuzzyRuleControllerTest {

    private FuzzyRuleRepository ruleRepository;
    private OntologyHistoryService historyService;
    private FuzzyRuleController controller;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(FuzzyRuleRepository.class);
        historyService = mock(OntologyHistoryService.class);
        controller = new FuzzyRuleController(ruleRepository, historyService);
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createRuleRecordsAHistoryEntry() {
        FuzzyRuleController.FuzzyRuleRequest request =
                new FuzzyRuleController.FuzzyRuleRequest("Rule1", "A", "B", true);

        ResponseEntity<FuzzyRuleEntity> response =
                controller.createRule("proj-1", request, "u1", "User One");

        FuzzyRuleEntity saved = response.getBody();
        verify(historyService).recordEdit(eq("proj-1"), eq("u1"), eq("User One"), eq("createFuzzyRule"),
                eq("urn:fuzzyrule:" + saved.getId()), eq("Rule1"), eq(null), eq("IF A THEN B"),
                eq("Created fuzzy rule: Rule1"));
    }

    @Test
    void updateRuleCapturesOldValuesBeforeMutating() {
        FuzzyRuleEntity existing = new FuzzyRuleEntity();
        existing.setId("rule-1");
        existing.setProjectId("proj-1");
        existing.setName("Rule1");
        existing.setCondition("A");
        existing.setAction("B");
        when(ruleRepository.findById("rule-1")).thenReturn(Optional.of(existing));

        FuzzyRuleController.FuzzyRuleRequest request =
                new FuzzyRuleController.FuzzyRuleRequest(null, "C", null, null);

        controller.updateRule("proj-1", "rule-1", request, "u1", "User One");

        verify(historyService).recordEdit(eq("proj-1"), eq("u1"), eq("User One"), eq("updateFuzzyRule"),
                eq("urn:fuzzyrule:rule-1"), eq("Rule1"), eq("IF A THEN B"), eq("IF C THEN B"),
                eq("Updated fuzzy rule: Rule1"));
    }

    @Test
    void deleteRuleRecordsOldValuesViaFindById() {
        FuzzyRuleEntity existing = new FuzzyRuleEntity();
        existing.setId("rule-1");
        existing.setProjectId("proj-1");
        existing.setName("Rule1");
        existing.setCondition("A");
        existing.setAction("B");
        when(ruleRepository.findById("rule-1")).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = controller.deleteRule("proj-1", "rule-1", "u1", "User One");

        assertEquals(200, response.getStatusCode().value());
        verify(ruleRepository).deleteById("rule-1");
        verify(historyService).recordEdit(eq("proj-1"), eq("u1"), eq("User One"), eq("deleteFuzzyRule"),
                eq("urn:fuzzyrule:rule-1"), eq("Rule1"), eq("IF A THEN B"), eq(null),
                eq("Deleted fuzzy rule: Rule1"));
    }

    @Test
    void deleteRuleReturnsNotFoundWhenRuleIsAbsent() {
        when(ruleRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteRule("proj-1", "missing", "u1", "User One");

        assertEquals(404, response.getStatusCode().value());
        verify(historyService, org.mockito.Mockito.never()).recordEdit(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq("deleteFuzzyRule"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
