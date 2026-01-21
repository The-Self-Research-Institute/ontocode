package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.FileMetadata;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends MongoRepository<FileMetadata, String> {
    
    Optional<FileMetadata> findByFileId(String fileId);
    
    // Find file excluding soft-deleted ones
    @Query("{ 'fileId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    Optional<FileMetadata> findActiveByFileId(String fileId);
    
    List<FileMetadata> findByProjectIdAndStatus(String projectId, String status);
    
    // Find files by project and status excluding soft-deleted ones
    @Query("{ 'projectId': ?0, 'status': ?1, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<FileMetadata> findActiveByProjectIdAndStatus(String projectId, String status);
    
    List<FileMetadata> findByWorkspaceIdAndStatus(String workspaceId, String status);
    
    List<FileMetadata> findByWorkspaceId(String workspaceId);
    
    // Find files by workspace and status excluding soft-deleted ones
    @Query("{ 'workspaceId': ?0, 'status': ?1, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<FileMetadata> findActiveByWorkspaceIdAndStatus(String workspaceId, String status);
    
    List<FileMetadata> findByUploadedBy(String userId);
    
    // Find files by uploader excluding soft-deleted ones
    @Query("{ 'uploadedBy': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<FileMetadata> findActiveByUploadedBy(String userId);
    
    void deleteByFileId(String fileId);
}
