package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMetadataService.class);
    
    private final ProjectRepository projectRepository;

    public ProjectMetadataService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public Optional<ProjectStatus> readStatus(String projectId) {
        return projectRepository.findById(projectId)
                .map(doc -> new ProjectStatus(
                        doc.getStatus(),
                        doc.getStatusMessage(),
                        doc.getUpdatedAt(),
                        doc.getFilename()
                ));
    }

    public void writeStatus(String projectId, ProjectStatus status) {
        ProjectDocument doc = projectRepository.findById(projectId)
                .orElse(new ProjectDocument(projectId, projectId, status.filename()));
        
        doc.setStatus(status.status());
        doc.setStatusMessage(status.statusMessage());
        doc.setFilename(status.filename());
        doc.setUpdatedAt(Instant.now());
        
        projectRepository.save(doc);
    }

    public Optional<Map<String, Object>> readMeta(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getMetadata);
    }

    public void writeMeta(String projectId, Map<String, Object> meta) {
        ProjectDocument doc = projectRepository.findById(projectId)
                .orElse(new ProjectDocument(projectId, projectId, null));
        
        meta.put("lastUpdated", Instant.now().toString());
        doc.setMetadata(meta);
        doc.setUpdatedAt(Instant.now());
        
        projectRepository.save(doc);
    }
    
    public void setOwnerEmail(String projectId, String ownerEmail) {
        ProjectDocument doc = projectRepository.findById(projectId)
                .orElse(new ProjectDocument(projectId, projectId, null));
        
        doc.setOwnerEmail(ownerEmail);
        doc.setUpdatedAt(Instant.now());
        
        projectRepository.save(doc);
    }
    
    public void setGridfsFileId(String projectId, String gridfsFileId) {
        ProjectDocument doc = projectRepository.findById(projectId)
                .orElse(new ProjectDocument(projectId, projectId, null));
        
        doc.setGridfsFileId(gridfsFileId);
        doc.setUpdatedAt(Instant.now());
        
        projectRepository.save(doc);
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
        return projectRepository.findAll().stream()
                .filter(doc -> filename.equals(doc.getFilename()))
                .map(ProjectDocument::getId)
                .findFirst();
    }

    /**
     * FIX: Batch update project metadata in a single database operation
     * Improves performance by avoiding 3 separate DB writes
     * Creates MongoDB project document for both cloud and self-hosted deployments
     */
    public void updateProjectMetadata(String projectId, ProjectStatus status, String gridfsFileId, String ownerEmail, String workspaceId, String parentProjectId) {
        log.info("[ProjectMetadataService] Updating project metadata - projectId: {}, owner: {}, workspace: {}, parentProject: {}, status: {}", 
            projectId, ownerEmail, workspaceId, parentProjectId, status.status());
            
        ProjectDocument doc = projectRepository.findById(projectId)
                .orElse(new ProjectDocument(projectId, projectId, status.filename()));

        // Update all fields in single operation
        doc.setStatus(status.status());
        doc.setStatusMessage(status.statusMessage());
        doc.setFilename(status.filename());
        doc.setGridfsFileId(gridfsFileId);

        if (ownerEmail != null && !ownerEmail.isEmpty()) {
            doc.setOwnerEmail(ownerEmail);
            log.info("[ProjectMetadataService] Setting owner email: {} for project: {}", ownerEmail, projectId);
        } else {
            log.warn("[ProjectMetadataService] No owner email provided for project: {}", projectId);
        }

        if (workspaceId != null && !workspaceId.isEmpty()) {
            doc.setWorkspaceId(workspaceId);
            log.info("[ProjectMetadataService] Setting workspace ID: {} for project: {}", workspaceId, projectId);
        }

        if (parentProjectId != null && !parentProjectId.isEmpty()) {
            doc.setProjectId(parentProjectId);
            log.info("[ProjectMetadataService] Setting parent project ID: {} for file: {}", parentProjectId, projectId);
        }

        doc.setUpdatedAt(Instant.now());

        // Single database write - saves to MongoDB for both cloud and self-hosted
        ProjectDocument savedDoc = projectRepository.save(doc);
        log.info("[ProjectMetadataService] ✓ Project saved to MongoDB - id: {}, owner: {}, filename: {}", 
            savedDoc.getId(), savedDoc.getOwnerEmail(), savedDoc.getFilename());
    }
    
    /**
     * Get the updatedAt timestamp for a project
     */
    public Instant getUpdatedAt(String projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectDocument::getUpdatedAt)
                .orElse(null);
    }
}