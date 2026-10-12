package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;
import self.research.ontology.owlEditor.repository.ProjectRecoveryLockRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectRecoveryLockServiceTest {

    private final Map<String, ProjectRecoveryLockDocument> store = new ConcurrentHashMap<>();
    private ProjectRecoveryLockRepository repository;
    private ProjectRecoveryLockService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectRecoveryLockRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            ProjectRecoveryLockDocument doc = invocation.getArgument(0);
            store.put(doc.getId(), doc);
            return doc;
        });
        when(repository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(store.get(invocation.<String>getArgument(0))));
        service = new ProjectRecoveryLockService(repository);
    }

    @Test
    void lockIsVisibleUntilReleased() {
        assertFalse(service.isLocked("proj-1"));

        service.lock("proj-1", "rollback failed", "op-1", true);

        assertTrue(service.isLocked("proj-1"));
        ProjectRecoveryLockDocument lock = service.findActiveLock("proj-1").orElseThrow();
        assertEquals("rollback failed", lock.getReason());
        assertEquals("op-1", lock.getOperationId());
        assertTrue(lock.isCanRestore());
        assertNotNull(lock.getLockedAt());
        assertFalse(service.isLocked("proj-2"));

        service.release("proj-1", "owner@x.com", "CLEARED");

        assertFalse(service.isLocked("proj-1"));
        assertTrue(service.findActiveLock("proj-1").isEmpty());
        assertEquals("owner@x.com", store.get("proj-1").getReleasedBy());
        assertEquals("CLEARED", store.get("proj-1").getReleaseAction());
    }

    @Test
    void releasingAnUnlockedProjectIsANoOp() {
        service.release("proj-9", "owner@x.com", "CLEARED");

        assertTrue(store.isEmpty());
    }

    @Test
    void blankProjectIsNeverLocked() {
        assertFalse(service.isLocked(null));
        assertFalse(service.isLocked(""));
    }

    @Test
    void storageFailureOnTheHotPathReadsAsUnlocked() {
        when(repository.findById(anyString())).thenThrow(new IllegalStateException("mongo down"));

        assertFalse(service.isLocked("proj-1"));
    }
}
