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

    @Query("{ 'fileId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    Optional<FileMetadata> findActiveByFileId(String fileId);

    List<FileMetadata> findByProjectIdAndStatus(String projectId, String status);

    @Query("{ 'projectId': ?0, 'status': ?1, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<FileMetadata> findActiveByProjectIdAndStatus(String projectId, String status);

    List<FileMetadata> findByWorkspaceIdAndStatus(String workspaceId, String status);

    List<FileMetadata> findByWorkspaceId(String workspaceId);

    @Query("{ 'workspaceId': ?0, 'status': ?1, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<FileMetadata> findActiveByWorkspaceIdAndStatus(String workspaceId, String status);

    List<FileMetadata> findByUploadedBy(String userId);

    @Query("{ 'uploadedBy': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<FileMetadata> findActiveByUploadedBy(String userId);

    void deleteByFileId(String fileId);
}
