package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectMetadataService {

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
        return projectRepository.findByFilenameAndOwnerEmail(filename, ownerEmail).isPresent();
    }
    
    public Optional<String> getExistingProjectId(String filename, String ownerEmail) {
        if (filename == null || ownerEmail == null) {
            return Optional.empty();
        }
        return projectRepository.findByFilenameAndOwnerEmail(filename, ownerEmail)
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
     */
    public void updateProjectMetadata(String projectId, ProjectStatus status, String gridfsFileId, String ownerEmail) {
        ProjectDocument doc = projectRepository.findById(projectId)
                .orElse(new ProjectDocument(projectId, projectId, status.filename()));

        // Update all fields in single operation
        doc.setStatus(status.status());
        doc.setStatusMessage(status.statusMessage());
        doc.setFilename(status.filename());
        doc.setGridfsFileId(gridfsFileId);

        if (ownerEmail != null && !ownerEmail.isEmpty()) {
            doc.setOwnerEmail(ownerEmail);
        }

        doc.setUpdatedAt(Instant.now());

        // Single database write
        projectRepository.save(doc);
    }
}