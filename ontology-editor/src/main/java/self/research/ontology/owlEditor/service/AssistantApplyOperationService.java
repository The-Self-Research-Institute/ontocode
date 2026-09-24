package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.repository.AssistantApplyOperationRepository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AssistantApplyOperationService {

    static final String SNAPSHOT_EXTENSION = ".owl";
    private static final Set<ApplyOperationStatus> IN_FLIGHT =
            EnumSet.of(ApplyOperationStatus.PREPARED, ApplyOperationStatus.GRAPH_IMPORTING);
    private static final Set<ApplyOperationStatus> POSSIBLY_UNRESOLVED =
            EnumSet.of(ApplyOperationStatus.PREPARED, ApplyOperationStatus.GRAPH_IMPORTING, ApplyOperationStatus.FAILED);
    private static final Set<ApplyOperationStatus> FINISHED =
            EnumSet.of(ApplyOperationStatus.COMMITTED, ApplyOperationStatus.ROLLED_BACK);

    private final AssistantApplyOperationRepository repository;
    private final StorageManager storageManager;
    private final Path snapshotDir;

    public AssistantApplyOperationService(AssistantApplyOperationRepository repository,
                                          StorageManager storageManager,
                                          @Value("${ontocode.data.dir:./data}") String rootDir) {
        this.repository = repository;
        this.storageManager = storageManager;
        this.snapshotDir = Path.of(rootDir).toAbsolutePath().normalize().resolve("assistant-apply-snapshots");
    }

    public Path snapshotDir() {
        return snapshotDir;
    }

    public AssistantApplyOperationDocument prepare(AssistantEditGroupDocument group, String actor) throws IOException {
        String operationId = UUID.randomUUID().toString();
        Files.createDirectories(snapshotDir);
        Path snapshot = snapshotDir.resolve(operationId + SNAPSHOT_EXTENSION);
        Path exported = storageManager.exportOntology(group.getProjectId(), "rdfxml");
        if (exported == null || !Files.exists(exported)) {
            throw new IOException("The pre-apply export produced no file");
        }
        Files.copy(exported, snapshot, StandardCopyOption.REPLACE_EXISTING);
        Instant now = Instant.now();
        AssistantApplyOperationDocument operation = AssistantApplyOperationDocument.builder()
                .id(operationId)
                .projectId(group.getProjectId())
                .groupId(group.getId())
                .sessionId(group.getSessionId())
                .actor(actor)
                .targetPath(group.getTargetPath())
                .status(ApplyOperationStatus.PREPARED)
                .snapshotPath(snapshot.toString())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return repository.save(operation);
        } catch (RuntimeException e) {
            Files.deleteIfExists(snapshot);
            throw e;
        }
    }

    public void markImporting(AssistantApplyOperationDocument operation) {
        operation.setStatus(ApplyOperationStatus.GRAPH_IMPORTING);
        operation.setUpdatedAt(Instant.now());
        repository.save(operation);
    }

    public void markCommitted(AssistantApplyOperationDocument operation) {
        finish(operation, ApplyOperationStatus.COMMITTED, null, "COMMITTED");
    }

    public void markRolledBack(AssistantApplyOperationDocument operation, String reason, String resolution) {
        finish(operation, ApplyOperationStatus.ROLLED_BACK, reason, resolution);
    }

    public void markAbandoned(AssistantApplyOperationDocument operation, String reason) {
        finish(operation, ApplyOperationStatus.FAILED, reason, "NOT_STARTED");
    }

    public void markClearedByUser(AssistantApplyOperationDocument operation) {
        finish(operation, ApplyOperationStatus.FAILED, operation.getFailureReason(), "CLEARED");
    }

    public void markFailedAwaitingRecovery(AssistantApplyOperationDocument operation, String reason) {
        operation.setStatus(ApplyOperationStatus.FAILED);
        operation.setFailureReason(reason);
        operation.setResolvedAt(null);
        operation.setUpdatedAt(Instant.now());
        repository.save(operation);
    }

    private void finish(AssistantApplyOperationDocument operation, ApplyOperationStatus status,
                        String reason, String resolution) {
        Instant now = Instant.now();
        operation.setStatus(status);
        if (reason != null) {
            operation.setFailureReason(reason);
        }
        operation.setResolution(resolution);
        operation.setResolvedAt(now);
        operation.setUpdatedAt(now);
        repository.save(operation);
        deleteSnapshot(operation);
    }

    public Optional<AssistantApplyOperationDocument> findById(String operationId) {
        if (operationId == null) {
            return Optional.empty();
        }
        return repository.findById(operationId);
    }

    public Optional<AssistantApplyOperationDocument> findUnresolvedForGroup(String groupId) {
        return repository.findFirstByGroupIdOrderByCreatedAtDesc(groupId)
                .filter(AssistantApplyOperationDocument::isUnresolved);
    }

    public List<AssistantApplyOperationDocument> findUnresolvedForProject(String projectId) {
        return repository.findByProjectIdAndStatusIn(projectId, POSSIBLY_UNRESOLVED).stream()
                .filter(AssistantApplyOperationDocument::isUnresolved)
                .toList();
    }

    public List<AssistantApplyOperationDocument> findInterrupted() {
        return repository.findByStatusIn(IN_FLIGHT);
    }

    public List<AssistantApplyOperationDocument> findAllUnresolved() {
        return repository.findByStatusIn(POSSIBLY_UNRESOLVED).stream()
                .filter(AssistantApplyOperationDocument::isUnresolved)
                .toList();
    }

    public boolean snapshotExists(AssistantApplyOperationDocument operation) {
        Path snapshot = snapshotOf(operation);
        return snapshot != null && Files.isRegularFile(snapshot);
    }

    public Path snapshotOf(AssistantApplyOperationDocument operation) {
        return operation.getSnapshotPath() != null ? Path.of(operation.getSnapshotPath()) : null;
    }

    public void deleteSnapshot(AssistantApplyOperationDocument operation) {
        Path snapshot = snapshotOf(operation);
        if (snapshot == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException e) {
            log.warn("[Assistant] Could not delete apply snapshot {}: {}", snapshot, e.getMessage());
        }
    }

    public int sweepOrphanSnapshots(Set<String> operationIdsToKeep) {
        if (!Files.isDirectory(snapshotDir)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(snapshotDir, "*" + SNAPSHOT_EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String operationId = name.substring(0, name.length() - SNAPSHOT_EXTENSION.length());
                if (!operationIdsToKeep.contains(operationId)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("[Assistant] Could not sweep apply snapshots in {}: {}", snapshotDir, e.getMessage());
        }
        return removed;
    }

    public long pruneFinishedOlderThan(Duration age) {
        return repository.deleteByStatusInAndUpdatedAtBefore(FINISHED, Instant.now().minus(age));
    }
}
