package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Service for managing ontology files in MongoDB GridFS
 * Maps projectId to GridFS file storage
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridFSFileService {
    
    private final GridFsTemplate gridFsTemplate;
    
    /**
     * Store file in GridFS and return the file ID
     * 
     * @param projectId The project identifier
     * @param filename Original filename
     * @param contentType File content type
     * @param inputStream File content
     * @return GridFS ObjectId as String
     */
    public String storeFile(String projectId, String filename, String contentType, InputStream inputStream) {
        try {
            // Store file with metadata
            ObjectId fileId = gridFsTemplate.store(
                inputStream, 
                filename,
                contentType,
                new org.bson.Document()
                    .append("projectId", projectId)
                    .append("originalFilename", filename)
                    .append("uploadDate", new java.util.Date())
            );
            
            log.info("Stored file in GridFS for project {}: fileId={}, filename={}", 
                     projectId, fileId.toString(), filename);
            
            return fileId.toString();
        } catch (Exception e) {
            log.error("Failed to store file in GridFS for project {}", projectId, e);
            throw new RuntimeException("Failed to store file in GridFS: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get file from GridFS by projectId
     * 
     * @param projectId The project identifier
     * @return GridFsResource containing the file
     */
    public Optional<GridFsResource> getFileByProjectId(String projectId) {
        try {
            Query query = new Query(Criteria.where("metadata.projectId").is(projectId));
            GridFSFile gridFSFile = gridFsTemplate.findOne(query);
            
            if (gridFSFile == null) {
                log.warn("No file found in GridFS for project {}", projectId);
                return Optional.empty();
            }
            
            GridFsResource resource = gridFsTemplate.getResource(gridFSFile);
            log.info("Retrieved file from GridFS for project {}: fileId={}", 
                     projectId, gridFSFile.getObjectId().toString());
            
            return Optional.of(resource);
        } catch (Exception e) {
            log.error("Failed to retrieve file from GridFS for project {}", projectId, e);
            return Optional.empty();
        }
    }
    
    /**
     * Get file from GridFS by ObjectId
     * 
     * @param fileId GridFS ObjectId
     * @return GridFsResource containing the file
     */
    public Optional<GridFsResource> getFileById(String fileId) {
        try {
            GridFSFile gridFSFile = gridFsTemplate.findOne(
                new Query(Criteria.where("_id").is(new ObjectId(fileId)))
            );
            
            if (gridFSFile == null) {
                log.warn("No file found in GridFS with id {}", fileId);
                return Optional.empty();
            }
            
            return Optional.of(gridFsTemplate.getResource(gridFSFile));
        } catch (Exception e) {
            log.error("Failed to retrieve file from GridFS with id {}", fileId, e);
            return Optional.empty();
        }
    }
    
    /**
     * Get file metadata from GridFS by projectId
     * 
     * @param projectId The project identifier
     * @return GridFSFile metadata
     */
    public Optional<GridFSFile> getFileMetadataByProjectId(String projectId) {
        try {
            Query query = new Query(Criteria.where("metadata.projectId").is(projectId));
            GridFSFile gridFSFile = gridFsTemplate.findOne(query);
            return Optional.ofNullable(gridFSFile);
        } catch (Exception e) {
            log.error("Failed to retrieve file metadata from GridFS for project {}", projectId, e);
            return Optional.empty();
        }
    }
    
    /**
     * Delete file from GridFS by projectId
     * 
     * @param projectId The project identifier
     */
    public void deleteFileByProjectId(String projectId) {
        try {
            Query query = new Query(Criteria.where("metadata.projectId").is(projectId));
            gridFsTemplate.delete(query);
            log.info("Deleted file from GridFS for project {}", projectId);
        } catch (Exception e) {
            log.error("Failed to delete file from GridFS for project {}", projectId, e);
            throw new RuntimeException("Failed to delete file from GridFS: " + e.getMessage(), e);
        }
    }
    
    /**
     * Delete file from GridFS by ObjectId
     * 
     * @param fileId GridFS ObjectId
     */
    public void deleteFileById(String fileId) {
        try {
            Query query = new Query(Criteria.where("_id").is(new ObjectId(fileId)));
            gridFsTemplate.delete(query);
            log.info("Deleted file from GridFS with id {}", fileId);
        } catch (Exception e) {
            log.error("Failed to delete file from GridFS with id {}", fileId, e);
            throw new RuntimeException("Failed to delete file from GridFS: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if file exists for projectId
     * 
     * @param projectId The project identifier
     * @return true if file exists
     */
    public boolean fileExistsForProject(String projectId) {
        Query query = new Query(Criteria.where("metadata.projectId").is(projectId));
        return gridFsTemplate.findOne(query) != null;
    }
}
