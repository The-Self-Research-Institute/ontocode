package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.ProjectAccessRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMetadataService.class);
    
    private final ProjectRepository projectRepository;
    private final ProjectAccessRepository accessRepository;

    public ProjectMetadataService(ProjectRepository projectRepository, ProjectAccessRepository accessRepository) {
        this.projectRepository = projectRepository;
        this.accessRepository = accessRepository;
    }

    public Optional<ProjectStatus> readStatus(String projectId) {
        Optional<ProjectDocument> doc = projectRepository.findById(projectId);
        return doc.map(d -> new ProjectStatus(
                        d.getStatus(),
                        d.getStatusMessage(),
                        d.getUpdatedAt(),
                        d.getFilename()
                ));
    }

    public void writeStatus(String projectId, ProjectStatus status) {
        projectRepository.upsertStatus(projectId, status.status(), status.statusMessage(), status.filename(), Instant.now());
    }

    public Optional<Map<String, Object>> readMeta(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getMetadata);
    }

    public void writeMeta(String projectId, Map<String, Object> meta) {
        Instant now = Instant.now();
        Map<String, Object> metadata = new HashMap<>(meta);
        metadata.put("lastUpdated", now.toString());
        projectRepository.upsertMetadata(projectId, metadata, "UPLOADED", now);
    }

    /** Persist import progress for polling clients (Project Library cards). */
    public void writeImportProgress(String projectId, int progress, String stage, String message) {
        Instant now = Instant.now();
        Map<String, Object> progressMeta = new HashMap<>();
        progressMeta.put("progress", Math.max(0, Math.min(99, progress)));
        if (stage != null && !stage.isBlank()) {
            progressMeta.put("stage", stage);
        }
        if (message != null && !message.isBlank()) {
            progressMeta.put("message", message);
        }
        progressMeta.put("lastUpdated", now.toString());
        projectRepository.upsertImportProgress(projectId, progressMeta, message != null ? message : "Importing…", now);
    }

    public void setOwnerEmail(String projectId, String ownerEmail) {
        projectRepository.upsertOwnerEmail(projectId, ownerEmail, Instant.now());
    }

    public void setGridfsFileId(String projectId, String gridfsFileId) {
        projectRepository.upsertBlobId(projectId, gridfsFileId, Instant.now());
    }

    public boolean isDuplicateFilename(String filename, String ownerEmail) {
        if (filename == null || ownerEmail == null) {
            return false;
        }
        return !projectRepository.findByFilenameAndOwnerEmail(filename, ownerEmail).isEmpty();
    }
    
    public Optional<String> getExistingProjectId(String filename, String ownerEmail) {
        if (filename == null || ownerEmail == null) {
            return Optional.empty();
        }
        return projectRepository.findFirstByFilenameAndOwnerEmailOrderByUpdatedAtDesc(filename, ownerEmail)
                .map(ProjectDocument::getId);
    }
    
    public Optional<String> getProjectIdByFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        return projectRepository.findFirstByFilenameOrderByUpdatedAtDesc(filename)
                .map(ProjectDocument::getId);
    }

    /**
     * FIX: Batch update project metadata in a single database operation
     * Improves performance by avoiding 3 separate DB writes
     * Creates the project document for both cloud and self-hosted deployments
     */
    public void updateProjectMetadata(String projectId, ProjectStatus status, String gridfsFileId, String ownerEmail, String ownerId, String workspaceId, String parentProjectId) {
        log.info("[ProjectMetadataService] Updating project metadata - projectId: {}, owner: {}, workspace: {}, parentProject: {}, status: {}", 
            projectId, ownerEmail, workspaceId, parentProjectId, status.status());
            
        if (ownerEmail != null && !ownerEmail.isEmpty()) {
            log.info("[ProjectMetadataService] Owner email {} applies only if project {} is new", ownerEmail, projectId);
        } else {
            log.warn("[ProjectMetadataService] No owner email provided for project: {}", projectId);
        }
        if (workspaceId != null && !workspaceId.isEmpty()) {
            log.info("[ProjectMetadataService] Setting workspace ID: {} for project: {}", workspaceId, projectId);
        }
        if (parentProjectId != null && !parentProjectId.isEmpty()) {
            log.info("[ProjectMetadataService] Setting parent project ID: {} for file: {}", parentProjectId, projectId);
        }

        projectRepository.upsertProjectMetadata(projectId, status.status(), status.statusMessage(), status.filename(),
                gridfsFileId, ownerEmail, ownerId, workspaceId, parentProjectId, Instant.now());
        log.info("[ProjectMetadataService] Project saved - id: {}, owner: {}, filename: {}",
            projectId, ownerEmail, status.filename());
    }
    
    public Optional<String> getOwnerEmail(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return accessRepository.findWorkspaceProject(ProjectAccessService.parentOf(projectId))
                .map(ProjectAccessRepository.WorkspaceProject::ownerEmail)
                .filter(email -> email != null && !email.isBlank())
                .or(() -> projectRepository.findById(projectId).map(ProjectDocument::getOwnerEmail));
    }

    public boolean isRequireDraftForMembers(String projectId) {
        return projectId != null && !projectId.isBlank()
                && projectRepository.isRequireDraftForMembers(ProjectAccessService.parentOf(projectId));
    }

    public void setRequireDraftForMembers(String projectId, boolean value) {
        projectRepository.setRequireDraftForMembers(projectId, ProjectAccessService.parentOf(projectId), value, Instant.now());
    }

    /**
     * Get the updatedAt timestamp for a project
     */
    public Instant getUpdatedAt(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getUpdatedAt)
                .orElse(null);
    }

    public String versionStamp(String projectId) {
        return projectRepository.findById(projectId)
                .map(doc -> (doc.getMutationVersion() != null ? doc.getMutationVersion() : 0L) + "@" + doc.getUpdatedAt())
                .orElse(null);
    }

    public long getMutationVersion(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getMutationVersion)
                .map(v -> v != null ? v : 0L)
                .orElse(0L);
    }

    /**
     * Synchronous version bump — must complete before mutation HTTP response returns
     * so other users' reads never see a stale OWLAPI model with a matching version.
     * Uses findAndModify so the returned value is the version THIS call wrote, not
     * a later one that raced in between a write + separate read.
     */
    public long incrementMutationVersion(String projectId) {
        return projectRepository.incrementMutationVersion(projectId, Instant.now());
    }

    public long incrementMainGraphRevision(String projectId) {
        return projectRepository.incrementMainGraphRevision(projectId, Instant.now());
    }

    public long getMainGraphRevision(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getMainGraphRevision)
                .orElse(0L);
    }
}
