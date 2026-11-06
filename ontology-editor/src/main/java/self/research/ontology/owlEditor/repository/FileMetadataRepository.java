package self.research.ontology.owlEditor.repository;

import java.util.Optional;

public interface FileMetadataRepository {

    record FileMetadataRecord(String fileId, String fileName, String projectId, String workspaceId, String blobId,
                              boolean deleted) {
    }

    Optional<FileMetadataRecord> findByFileId(String fileId);

    Optional<FileMetadataRecord> findActiveByFileId(String fileId);
}
