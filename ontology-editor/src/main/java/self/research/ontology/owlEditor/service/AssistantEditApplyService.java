package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AssistantEditApplyService {

    private final AssistantEditGroupRepository groupRepository;
    private final StorageManager storageManager;
    private final LineRangeSpliceWriter spliceWriter;
    private final CodeViewReimportPipeline reimportPipeline;
    private final AssistantEditGroupRemapService remapService;
    private final ProjectWriteLockRegistry lockRegistry;

    public AssistantEditApplyService(AssistantEditGroupRepository groupRepository,
                                      StorageManager storageManager,
                                      LineRangeSpliceWriter spliceWriter,
                                      CodeViewReimportPipeline reimportPipeline,
                                      AssistantEditGroupRemapService remapService,
                                      ProjectWriteLockRegistry lockRegistry) {
        this.groupRepository = groupRepository;
        this.storageManager = storageManager;
        this.spliceWriter = spliceWriter;
        this.reimportPipeline = reimportPipeline;
        this.remapService = remapService;
        this.lockRegistry = lockRegistry;
    }

    public ApplyResult applyGroup(String serverGroupId, String userEmail) {
        Optional<AssistantEditGroupDocument> initial = groupRepository.findById(serverGroupId);
        if (initial.isEmpty() || !initial.get().getUserEmail().equals(userEmail)) {
            return errorResult("VALIDATION_FAILED", "Unknown or unauthorized proposal");
        }
        String projectId = initial.get().getProjectId();

        try {
            return lockRegistry.runExclusive(projectId, () -> applyLocked(serverGroupId, userEmail));
        } catch (Exception e) {
            log.error("[Assistant] Unexpected failure applying group {}: {}", serverGroupId, e.getMessage(), e);
            return errorResult("APPLY_FAILED", e.getMessage() != null ? e.getMessage() : "Apply failed");
        }
    }

    private ApplyResult applyLocked(String serverGroupId, String userEmail) throws IOException {
        AssistantEditGroupDocument group = groupRepository.findById(serverGroupId).orElse(null);
        if (group == null || !group.getUserEmail().equals(userEmail)) {
            return errorResult("VALIDATION_FAILED", "Unknown or unauthorized proposal");
        }

        switch (group.getStatus()) {
            case APPLIED:
                return idempotentReplay(group);
            case DISCARDED:
            case VALIDATION_FAILED:
                return errorResult("VALIDATION_FAILED", "Proposal is not applicable");
            case STALE:
                return errorResult("STALE_GROUP", group.getStaleReason());
            case CONFLICT:
                return errorResult("CONFLICT", "Document changed since this group was checked");
            case PENDING:
                break;
        }

        boolean versionUnchanged = storageManager.getPublicGraphVersion(group.getProjectId())
                == group.getPublicGraphVersionAtPropose();
        if (!versionUnchanged && hasLiveMismatch(group)) {
            group.setStatus(AssistantEditGroupStatus.CONFLICT);
            group.setUpdatedAt(Instant.now());
            groupRepository.save(group);
            return errorResult("CONFLICT", "Document changed since this group was checked");
        }

        Path sourceFile = storageManager.ensureCodeViewFile(group.getProjectId(), group.getTargetPath());
        Path oldSnapshotFile = captureOldSnapshotForDiff(group.getProjectId());

        List<LineRangeSpliceWriter.SpliceEdit> spliceEdits = group.getEdits().stream()
                .map(e -> new LineRangeSpliceWriter.SpliceEdit(e.getStartLine(), e.getLineCount(), e.getNewText()))
                .toList();
        String extension = storageManager.extensionFor(group.getTargetPath());
        Path splicedFile = spliceWriter.splice(sourceFile, extension, spliceEdits);

        try {
            CodeViewReimportPipeline.ReimportResult reimportResult = reimportPipeline.reimport(
                    new CodeViewReimportPipeline.ReimportRequest(
                            group.getProjectId(), group.getTargetPath(), splicedFile, false,
                            userEmail, userEmail, null, oldSnapshotFile));

            group.setStatus(AssistantEditGroupStatus.APPLIED);
            group.setAppliedRevision(reimportResult.sourceVersion());
            group.setAppliedAt(Instant.now());
            group.setUpdatedAt(Instant.now());
            groupRepository.save(group);

            List<AssistantEditGroupDocument> siblings = new ArrayList<>(groupRepository.findByProjectIdAndTargetPathAndStatus(
                    group.getProjectId(), group.getTargetPath(), AssistantEditGroupStatus.PENDING));
            siblings.removeIf(sibling -> sibling.getId().equals(group.getId()));
            List<AssistantEditGroupDocument> touched = remapService.remap(group, siblings);
            if (!touched.isEmpty()) {
                groupRepository.saveAll(touched);
            }

            List<RemappedGroupInfo> remappedInfo = new ArrayList<>();
            for (AssistantEditGroupDocument sibling : siblings) {
                remappedInfo.add(new RemappedGroupInfo(sibling.getId(), touched.contains(sibling)));
            }

            log.info("[Assistant] Applied group {} for project {}, revision {}, {} sibling group(s) touched",
                    group.getId(), group.getProjectId(), reimportResult.sourceVersion(), touched.size());

            return ApplyResult.builder().ok(true).applied(true)
                    .newRevision(reimportResult.sourceVersion())
                    .remappedPendingGroups(remappedInfo)
                    .build();
        } finally {
            Files.deleteIfExists(splicedFile);
        }
    }

    private boolean hasLiveMismatch(AssistantEditGroupDocument group) throws IOException {
        for (EditEntry edit : group.getEdits()) {
            if (edit.getLineCount() == 0) {
                continue;
            }
            StorageManager.CodeViewPage page = storageManager.readCodeViewPage(
                    group.getProjectId(), group.getTargetPath(), edit.getStartLine(), edit.getLineCount());
            if (!page.content().equals(edit.getOriginalText())) {
                return true;
            }
        }
        return false;
    }

    private Path captureOldSnapshotForDiff(String projectId) {
        try {
            return storageManager.exportOntology(projectId, "rdfxml");
        } catch (Exception e) {
            log.warn("[Assistant] Could not capture pre-apply snapshot for change history: {}", e.getMessage());
            return null;
        }
    }

    private ApplyResult idempotentReplay(AssistantEditGroupDocument group) {
        return ApplyResult.builder().ok(true).applied(true)
                .newRevision(group.getAppliedRevision())
                .remappedPendingGroups(List.of())
                .build();
    }

    private ApplyResult errorResult(String errorCode, String message) {
        return ApplyResult.builder().ok(false).errorCode(errorCode).message(message).build();
    }

    public record RemappedGroupInfo(String serverGroupId, boolean remapped) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyResult {
        private boolean ok;
        private boolean applied;
        private Long newRevision;
        private List<RemappedGroupInfo> remappedPendingGroups;
        private String errorCode;
        private String message;
    }
}
