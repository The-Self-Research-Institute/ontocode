package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;
import self.research.ontology.owlEditor.repository.JdbcProjectRecoveryLockRepository;
import self.research.ontology.owlEditor.repository.ProjectRecoveryLockRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectRecoveryLockServiceTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final ProjectRecoveryLockRepository repository = new JdbcProjectRecoveryLockRepository(jdbc);
    private final ProjectRecoveryLockService service = new ProjectRecoveryLockService(repository);

    @BeforeEach
    void clean() {
        jdbc.update("delete from project_recovery_lock");
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
        ProjectRecoveryLockDocument stored = repository.findById("proj-1").orElseThrow();
        assertEquals("owner@x.com", stored.getReleasedBy());
        assertEquals("CLEARED", stored.getReleaseAction());
        assertNotNull(stored.getReleasedAt());
    }

    @Test
    void releasingAnUnlockedProjectIsANoOp() {
        service.release("proj-9", "owner@x.com", "CLEARED");

        assertTrue(repository.findById("proj-9").isEmpty());
    }

    @Test
    void blankProjectIsNeverLocked() {
        assertFalse(service.isLocked(null));
        assertFalse(service.isLocked(""));
    }

    @Test
    void storageFailureOnTheHotPathReadsAsUnlocked() {
        ProjectRecoveryLockRepository broken = mock(ProjectRecoveryLockRepository.class);
        when(broken.findById(anyString())).thenThrow(new IllegalStateException("database down"));

        assertFalse(new ProjectRecoveryLockService(broken).isLocked("proj-1"));
    }

    @Test
    void lockingAgainAfterARelease() {
        service.lock("proj-1", "first", "op-1", false);
        service.release("proj-1", "a", "CLEARED");
        service.lock("proj-1", "second", "op-2", true);

        Optional<ProjectRecoveryLockDocument> active = service.findActiveLock("proj-1");
        assertEquals("second", active.orElseThrow().getReason());
    }
}
