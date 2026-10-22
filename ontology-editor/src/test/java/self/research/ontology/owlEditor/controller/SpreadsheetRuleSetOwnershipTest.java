package self.research.ontology.owlEditor.controller;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;
import self.research.ontology.owlEditor.repository.SpreadsheetRuleSetRepository;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetRuleSetService;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpreadsheetRuleSetOwnershipTest {

    private static final String PROJECT = "proj-1--file-1";

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private SpreadsheetRuleSetRepository repository;
    private WorkspaceOwnershipService ownership;
    private SpreadsheetRuleSetController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SpreadsheetRuleSetRepository.class);
        ownership = mock(WorkspaceOwnershipService.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByProjectIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        controller = new SpreadsheetRuleSetController(new SpreadsheetRuleSetService(repository));
        controller.setWorkspaceOwnership(ownership);
    }

    private MockHttpServletRequest as(String userId, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer "
            + Jwts.builder().claims(Map.of("userId", userId, "email", email)).signWith(key).compact());
        return request;
    }

    private void stored(String id, String createdBy, String createdByUserId) {
        SpreadsheetRuleSetEntity e = new SpreadsheetRuleSetEntity();
        e.setId(id);
        e.setProjectId(PROJECT);
        e.setName("Rules " + id);
        e.setRules(List.of(new RuleSpec("Readings", "A", "A", "2", "+", "Individual: @A*", "", true)));
        e.setCreatedBy(createdBy);
        e.setCreatedByUserId(createdByUserId);
        when(repository.findById(id)).thenReturn(Optional.of(e));
    }

    private SpreadsheetRuleSetController.RuleSetRequest request() {
        return new SpreadsheetRuleSetController.RuleSetRequest("Renamed", null,
            List.of(new RuleSpec("Readings", "A", "A", "2", "+", "Individual: @A*", "", true)), null);
    }

    @Test
    void aDraftEditorCannotDeleteOrChangeSomeoneElsesRuleSet() {
        when(ownership.isDraftEditorInProject("bob", PROJECT)).thenReturn(true);
        stored("r1", "anna@example.org", "anna");

        assertEquals(403, controller.delete(PROJECT, "r1", as("bob", "bob@example.org")).getStatusCode().value());
        assertEquals(403, controller.update(PROJECT, "r1", request(), as("bob", "bob@example.org")).getStatusCode().value());
        verify(repository, never()).delete(any(SpreadsheetRuleSetEntity.class));
        verify(repository, never()).save(any());
    }

    @Test
    void aDraftEditorCanDeleteAndChangeTheirOwnRuleSet() {
        when(ownership.isDraftEditorInProject("bob", PROJECT)).thenReturn(true);
        stored("r2", "bob@example.org", "bob");

        assertEquals(200, controller.update(PROJECT, "r2", request(), as("bob", "bob@example.org")).getStatusCode().value());
        assertEquals(200, controller.delete(PROJECT, "r2", as("bob", "bob@example.org")).getStatusCode().value());
    }

    @Test
    void anOlderRuleSetWithoutAUserIdIsMatchedByEmail() {
        when(ownership.isDraftEditorInProject("bob", PROJECT)).thenReturn(true);
        stored("r3", "Bob@Example.org", null);

        assertEquals(200, controller.delete(PROJECT, "r3", as("bob", "bob@example.org")).getStatusCode().value());
    }

    @Test
    void anEditorCanStillDeleteAnyRuleSet() {
        when(ownership.isDraftEditorInProject("carol", PROJECT)).thenReturn(false);
        stored("r4", "anna@example.org", "anna");

        assertEquals(200, controller.delete(PROJECT, "r4", as("carol", "carol@example.org")).getStatusCode().value());
    }

    @Test
    void aNewRuleSetRecordsWhoCreatedIt() {
        controller.create(PROJECT, request(), as("bob", "bob@example.org"));

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(e ->
            "bob".equals(e.getCreatedByUserId()) && "bob@example.org".equals(e.getCreatedBy())));
    }
}
