package self.research.ontology.owlEditor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.StoredFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlobFileService {

    public static final String BUCKET = "fs";

    public static final String ONTOLOGY_FILE_TYPE = "ontology-file";

    private final BlobStore blobStore;

    public String storeFile(String projectId, String filename, String contentType, InputStream inputStream) {
        try {
            StoredFile stored = blobStore.store(BUCKET, filename, contentType,
                    Map.of("projectId", projectId, "originalFilename", filename, "type", ONTOLOGY_FILE_TYPE), inputStream);
            log.info("Stored file for project {}: fileId={}, filename={}", projectId, stored.id(), filename);
            return stored.id();
        } catch (Exception e) {
            log.error("Failed to store file for project {}", projectId, e);
            throw new RuntimeException("Failed to store file: " + e.getMessage(), e);
        }
    }

    public Optional<BlobResource> getFileByProjectId(String projectId) {
        try {
            Optional<StoredFile> file = latestOntologyFile(projectId);
            if (file.isEmpty()) {
                log.warn("No stored file found for project {}", projectId);
                return Optional.empty();
            }
            log.info("Retrieved stored file for project {}: fileId={}", projectId, file.get().id());
            return Optional.of(new BlobResource(blobStore, file.get()));
        } catch (Exception e) {
            log.error("Failed to retrieve stored file for project {}", projectId, e);
            return Optional.empty();
        }
    }

    public Optional<BlobResource> getFileById(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<StoredFile> file = blobStore.info(fileId);
            if (file.isEmpty()) {
                log.warn("No stored file found with id {}", fileId);
                return Optional.empty();
            }
            return Optional.of(new BlobResource(blobStore, file.get()));
        } catch (Exception e) {
            log.error("Failed to retrieve stored file with id {}", fileId, e);
            return Optional.empty();
        }
    }

    public Optional<BlobResource> getFileByName(String filename) {
        try {
            return blobStore.findLatest(BUCKET, filename, null).map(file -> new BlobResource(blobStore, file));
        } catch (Exception e) {
            log.error("Failed to retrieve stored file named {}", filename, e);
            return Optional.empty();
        }
    }

    public Optional<StoredFile> getFileMetadataByProjectId(String projectId) {
        try {
            return latestOntologyFile(projectId);
        } catch (Exception e) {
            log.error("Failed to retrieve file metadata for project {}", projectId, e);
            return Optional.empty();
        }
    }

    public void deleteFileByProjectId(String projectId) {
        try {
            for (StoredFile file : ontologyFiles(projectId)) {
                blobStore.delete(file.id());
            }
            log.info("Deleted stored files for project {}", projectId);
        } catch (Exception e) {
            log.error("Failed to delete stored files for project {}", projectId, e);
            throw new RuntimeException("Failed to delete stored file: " + e.getMessage(), e);
        }
    }

    public void deleteFileById(String fileId) {
        try {
            blobStore.delete(fileId);
            log.info("Deleted stored file with id {}", fileId);
        } catch (Exception e) {
            log.error("Failed to delete stored file with id {}", fileId, e);
            throw new RuntimeException("Failed to delete stored file: " + e.getMessage(), e);
        }
    }

    public boolean fileExistsForProject(String projectId) {
        return latestOntologyFile(projectId).isPresent();
    }

    private Optional<StoredFile> latestOntologyFile(String projectId) {
        return ontologyFiles(projectId).stream().findFirst();
    }

    private List<StoredFile> ontologyFiles(String projectId) {
        return blobStore.find(BUCKET, null, Map.of("projectId", projectId)).stream()
                .filter(BlobFileService::isOntologyFile).toList();
    }

    private static boolean isOntologyFile(StoredFile file) {
        Object type = file.metadata() == null ? null : file.metadata().get("type");
        return type == null || ONTOLOGY_FILE_TYPE.equals(type);
    }
}
