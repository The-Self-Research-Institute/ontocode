package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantAuditEntryDocument;
import self.research.ontology.owlEditor.repository.AssistantAuditRepository;
import self.research.ontology.owlEditor.service.AssistantAuditService.AssistantAuditEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantAuditServiceTest {

    @Mock
    private AssistantAuditRepository repository;

    private AssistantAuditService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AssistantAuditService(repository);
    }

    @Test
    void recordPersistsEveryField() {
        service.record(new AssistantAuditEvent("u@x.com", "proj-1", "s1", "g1", "apply", 42L,
                "claude", "claude-sonnet", "ok", null, null));

        ArgumentCaptor<AssistantAuditEntryDocument> captor = ArgumentCaptor.forClass(AssistantAuditEntryDocument.class);
        verify(repository).save(captor.capture());
        AssistantAuditEntryDocument saved = captor.getValue();
        assertEquals("u@x.com", saved.getActor());
        assertEquals("proj-1", saved.getProjectId());
        assertEquals("g1", saved.getGroupId());
        assertEquals("apply", saved.getOperation());
        assertEquals(42L, saved.getSourceRevision());
        assertEquals("claude-sonnet", saved.getModel());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void persistenceFailureNeverBreaksTheCallingOperation() {
        when(repository.save(any())).thenThrow(new RuntimeException("mongo down"));

        assertDoesNotThrow(() -> service.record(new AssistantAuditEvent("u@x.com", "proj-1", "s1", null,
                "propose", 7L, null, null, "failed", "VALIDATION_FAILED", null)));
    }
}
