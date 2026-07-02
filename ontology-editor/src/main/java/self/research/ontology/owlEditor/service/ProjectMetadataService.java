package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMetadataService.class);
    
    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;

    public ProjectMetadataService(ProjectRepository projectRepository, MongoTemplate mongoTemplate) {
        this.projectRepository = projectRepository;
        this.mongoTemplate = mongoTemplate;
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
        Instant now = Instant.now();
        Update update = newProjectUpdate(projectId, now)
                .set("status", status.status())
                .set("statusMessage", status.statusMessage())
                .set("filename", status.filename())
                .set("updatedAt", now);

        mongoTemplate.upsert(projectQuery(projectId), update, ProjectDocument.class);
    }

    public Optional<Map<String, Object>> readMeta(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getMetadata);
    }

    public void writeMeta(String projectId, Map<String, Object> meta) {
        Instant now = Instant.now();
        Map<String, Object> metadata = new HashMap<>(meta);
        metadata.put("lastUpdated", now.toString());

        Update update = newProjectUpdate(projectId, now)
                .setOnInsert("status", "UPLOADED")
                .set("metadata", metadata)
                .set("updatedAt", now);

        mongoTemplate.upsert(projectQuery(projectId), update, ProjectDocument.class);
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

        Update update = newProjectUpdate(projectId, now)
                .set("statusMessage", message != null ? message : "Importing…")
                .set("metadata.importProgress", progressMeta)
                .set("updatedAt", now);

        mongoTemplate.upsert(projectQuery(projectId), update, ProjectDocument.class);
    }
    
    public void setOwnerEmail(String projectId, String ownerEmail) {
        Instant now = Instant.now();
        Update update = newProjectUpdate(projectId, now)
                .setOnInsert("status", "UPLOADED")
                .set("ownerEmail", ownerEmail)
                .set("updatedAt", now);

        mongoTemplate.upsert(projectQuery(projectId), update, ProjectDocument.class);
    }
    
    public void setGridfsFileId(String projectId, String gridfsFileId) {
        Instant now = Instant.now();
        Update update = newProjectUpdate(projectId, now)
                .setOnInsert("status", "UPLOADED")
                .set("gridfsFileId", gridfsFileId)
                .set("updatedAt", now);

        mongoTemplate.upsert(projectQuery(projectId), update, ProjectDocument.class);
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
     * Creates MongoDB project document for both cloud and self-hosted deployments
     */
    public void updateProjectMetadata(String projectId, ProjectStatus status, String gridfsFileId, String ownerEmail, String workspaceId, String parentProjectId) {
        log.info("[ProjectMetadataService] Updating project metadata - projectId: {}, owner: {}, workspace: {}, parentProject: {}, status: {}", 
            projectId, ownerEmail, workspaceId, parentProjectId, status.status());
            
        Instant now = Instant.now();
        Update update = newProjectUpdate(projectId, now)
                .set("status", status.status())
                .set("statusMessage", status.statusMessage())
                .set("filename", status.filename())
                .set("gridfsFileId", gridfsFileId)
                .set("updatedAt", now);

        if (ownerEmail != null && !ownerEmail.isEmpty()) {
            update.set("ownerEmail", ownerEmail);
            log.info("[ProjectMetadataService] Setting owner email: {} for project: {}", ownerEmail, projectId);
        } else {
            log.warn("[ProjectMetadataService] No owner email provided for project: {}", projectId);
        }

        if (workspaceId != null && !workspaceId.isEmpty()) {
            update.set("workspaceId", workspaceId);
            log.info("[ProjectMetadataService] Setting workspace ID: {} for project: {}", workspaceId, projectId);
        }

        if (parentProjectId != null && !parentProjectId.isEmpty()) {
            update.set("projectId", parentProjectId);
            log.info("[ProjectMetadataService] Setting parent project ID: {} for file: {}", parentProjectId, projectId);
        }

        mongoTemplate.upsert(projectQuery(projectId), update, ProjectDocument.class);
        log.info("[ProjectMetadataService] ✓ Project saved to MongoDB - id: {}, owner: {}, filename: {}", 
            projectId, ownerEmail, status.filename());
    }
    
    public Optional<String> getOwnerEmail(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getOwnerEmail);
    }

    public boolean isRequireDraftForMembers(String projectId) {
        return readMeta(projectId)
                .map(m -> Boolean.TRUE.equals(m.get("requireDraftForMembers")))
                .orElse(false);
    }

    public void setRequireDraftForMembers(String projectId, boolean value) {
        Map<String, Object> meta = readMeta(projectId).map(HashMap::new).orElseGet(HashMap::new);
        meta.put("requireDraftForMembers", value);
        writeMeta(projectId, meta);
    }

    /**
     * Get the updatedAt timestamp for a project
     */
    public Instant getUpdatedAt(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getUpdatedAt)
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
        Instant now = Instant.now();
        Update update = new Update()
                .inc("mutationVersion", 1)
                .set("updatedAt", now);
        ProjectDocument updated = mongoTemplate.findAndModify(
                projectQuery(projectId),
                update,
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        return updated != null && updated.getMutationVersion() != null
                ? updated.getMutationVersion() : 0L;
    }

    private Query projectQuery(String projectId) {
        return Query.query(Criteria.where("_id").is(projectId));
    }

    private Update newProjectUpdate(String projectId, Instant now) {
        return new Update()
                .setOnInsert("name", projectId)
                .setOnInsert("createdAt", now);
    }
}
