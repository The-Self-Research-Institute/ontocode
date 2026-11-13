package self.research.ontology.plugins.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.StoredFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class BlobPluginStorageService implements PluginStorageService {

    static final String BUCKET = "plugins";

    private final BlobStore blobs;

    public BlobPluginStorageService(BlobStore blobs) {
        this.blobs = blobs;
    }

    @Override
    public String uploadPlugin(InputStream fileStream, String fileName, String contentType, PluginMetadata metadata) {
        try {
            log.info("Uploading plugin file: {} for plugin: {}", fileName, metadata.getPluginId());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("pluginId", metadata.getPluginId());
            meta.put("version", metadata.getVersion());
            meta.put("author", metadata.getAuthor());
            meta.put("uploadedAt", LocalDateTime.now().toString());
            StoredFile stored = blobs.store(BUCKET, fileName, contentType, meta, fileStream);
            log.info("Plugin file uploaded successfully with ID: {}", stored.id());
            return stored.id();
        } catch (Exception e) {
            log.error("Failed to upload plugin file: {}", fileName, e);
            throw new StorageException("Failed to upload plugin file", e);
        }
    }

    @Override
    public InputStream downloadPlugin(String fileId) {
        try {
            requirePluginFile(fileId);
            return blobs.open(fileId);
        } catch (Exception e) {
            log.error("Failed to download plugin file: {}", fileId, e);
            throw new StorageException("Failed to download plugin file", e);
        }
    }

    @Override
    public void deletePlugin(String fileId) {
        try {
            blobs.delete(fileId);
            log.info("Plugin file deleted successfully: {}", fileId);
        } catch (Exception e) {
            log.error("Failed to delete plugin file: {}", fileId, e);
            throw new StorageException("Failed to delete plugin file", e);
        }
    }

    @Override
    public StorageMetadata getMetadata(String fileId) {
        try {
            StoredFile file = requirePluginFile(fileId);
            Map<String, Object> meta = file.metadata();
            PluginMetadata pluginMetadata = PluginMetadata.builder()
                    .pluginId((String) meta.get("pluginId"))
                    .version((String) meta.get("version"))
                    .author((String) meta.get("author"))
                    .build();
            return StorageMetadata.builder()
                    .fileId(fileId)
                    .fileName(file.filename())
                    .contentType(file.contentType())
                    .fileSize(file.length())
                    .uploadedAt(file.uploadDate() != null ? LocalDateTime.ofInstant(file.uploadDate(), ZoneId.systemDefault()) : null)
                    .pluginMetadata(pluginMetadata)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get metadata for file: {}", fileId, e);
            throw new StorageException("Failed to get file metadata", e);
        }
    }

    @Override
    public boolean exists(String fileId) {
        try {
            return blobs.info(fileId).filter(f -> BUCKET.equals(f.bucket())).isPresent();
        } catch (Exception e) {
            log.error("Failed to check file existence: {}", fileId, e);
            return false;
        }
    }

    private StoredFile requirePluginFile(String fileId) {
        return blobs.info(fileId).filter(f -> BUCKET.equals(f.bucket()))
                .orElseThrow(() -> new StorageException("Plugin file not found: " + fileId));
    }
}
