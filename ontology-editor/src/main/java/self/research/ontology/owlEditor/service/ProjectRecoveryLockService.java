package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;
import self.research.ontology.owlEditor.repository.ProjectRecoveryLockRepository;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
public class ProjectRecoveryLockService {

    private final ProjectRecoveryLockRepository repository;

    public ProjectRecoveryLockService(ProjectRecoveryLockRepository repository) {
        this.repository = repository;
    }

    public boolean isLocked(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        try {
            return repository.findById(projectId).map(ProjectRecoveryLockDocument::isLocked).orElse(false);
        } catch (Exception e) {
            log.warn("[Recovery] Could not read the recovery lock for project {}: {}", projectId, e.getMessage());
            return false;
        }
    }

    public Optional<ProjectRecoveryLockDocument> findActiveLock(String projectId) {
        return repository.findById(projectId).filter(ProjectRecoveryLockDocument::isLocked);
    }

    public ProjectRecoveryLockDocument lock(String projectId, String reason, String operationId, boolean canRestore) {
        Instant now = Instant.now();
        ProjectRecoveryLockDocument lock = ProjectRecoveryLockDocument.builder()
                .id(projectId)
                .locked(true)
                .reason(reason)
                .lockedAt(now)
                .operationId(operationId)
                .canRestore(canRestore)
                .updatedAt(now)
                .build();
        log.error("[Recovery] Locking project {} for changes until it is restored or cleared: {}", projectId, reason);
        return repository.save(lock);
    }

    public void release(String projectId, String actor, String action) {
        Optional<ProjectRecoveryLockDocument> existing = repository.findById(projectId);
        if (existing.isEmpty() || !existing.get().isLocked()) {
            return;
        }
        ProjectRecoveryLockDocument lock = existing.get();
        Instant now = Instant.now();
        lock.setLocked(false);
        lock.setReleasedBy(actor);
        lock.setReleaseAction(action);
        lock.setReleasedAt(now);
        lock.setUpdatedAt(now);
        repository.save(lock);
        log.info("[Recovery] Project {} unlocked by {} ({})", projectId, actor, action);
    }
}
