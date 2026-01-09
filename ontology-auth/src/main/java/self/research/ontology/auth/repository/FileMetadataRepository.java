package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.FileMetadata;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends MongoRepository<FileMetadata, String> {
    
    Optional<FileMetadata> findByFileId(String fileId);
    
    List<FileMetadata> findByProjectIdAndStatus(String projectId, String status);
    
    List<FileMetadata> findByWorkspaceIdAndStatus(String workspaceId, String status);
    
    List<FileMetadata> findByUploadedBy(String userId);
    
    void deleteByFileId(String fileId);
}
