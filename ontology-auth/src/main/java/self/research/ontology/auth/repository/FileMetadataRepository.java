package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.FileMetadata;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileMetadataRepository {

    Optional<FileMetadata> findById(String id);

    FileMetadata save(FileMetadata metadata);

    void deleteById(String id);

    void deleteAll(Iterable<? extends FileMetadata> files);

    List<FileMetadata> findAllByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff);

    Optional<FileMetadata> findByFileId(String fileId);

    List<FileMetadata> findByProjectId(String projectId);

    List<FileMetadata> findByProjectIdAndStatus(String projectId, String status);

    List<FileMetadata> findByWorkspaceId(String workspaceId);

    List<FileMetadata> findByWorkspaceIdAndStatus(String workspaceId, String status);
}
