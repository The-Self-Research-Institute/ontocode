package self.research.ontology.plugins.storage;

import com.mongodb.client.gridfs.model.GridFSFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * GridFS implementation of PluginStorageService for Phase 1 (Zero Cost).
 * Can be replaced with S3StorageService in Phase 2 without changing any calling code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridFSStorageService implements PluginStorageService {

    private final GridFsTemplate gridFsTemplate;

    @Override
    public String uploadPlugin(InputStream fileStream, String fileName, String contentType, PluginMetadata metadata) {
        try {
            log.info("Uploading plugin file: {} for plugin: {}", fileName, metadata.getPluginId());

            Document metadataDoc = new Document();
            metadataDoc.put("pluginId", metadata.getPluginId());
            metadataDoc.put("version", metadata.getVersion());
            metadataDoc.put("author", metadata.getAuthor());
            metadataDoc.put("uploadedAt", LocalDateTime.now().toString());

            ObjectId fileId = gridFsTemplate.store(
                fileStream,
                fileName,
                contentType,
                metadataDoc
            );

            log.info("Plugin file uploaded successfully with ID: {}", fileId.toString());
            return fileId.toString();

        } catch (Exception e) {
            log.error("Failed to upload plugin file: {}", fileName, e);
            throw new StorageException("Failed to upload plugin file", e);
        }
    }

    @Override
    public InputStream downloadPlugin(String fileId) {
        try {
            log.info("Downloading plugin file with ID: {}", fileId);

            GridFSFile file = gridFsTemplate.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(fileId)))
            );

            if (file == null) {
                throw new StorageException("Plugin file not found: " + fileId);
            }

            GridFsResource resource = gridFsTemplate.getResource(file);
            return resource.getInputStream();

        } catch (Exception e) {
            log.error("Failed to download plugin file: {}", fileId, e);
            throw new StorageException("Failed to download plugin file", e);
        }
    }

    @Override
    public void deletePlugin(String fileId) {
        try {
            log.info("Deleting plugin file with ID: {}", fileId);
            gridFsTemplate.delete(Query.query(Criteria.where("_id").is(new ObjectId(fileId))));
            log.info("Plugin file deleted successfully: {}", fileId);

        } catch (Exception e) {
            log.error("Failed to delete plugin file: {}", fileId, e);
            throw new StorageException("Failed to delete plugin file", e);
        }
    }

    @Override
    public StorageMetadata getMetadata(String fileId) {
        try {
            GridFSFile file = gridFsTemplate.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(fileId)))
            );

            if (file == null) {
                throw new StorageException("Plugin file not found: " + fileId);
            }

            Document metadata = file.getMetadata();
            PluginMetadata pluginMetadata = null;

            if (metadata != null) {
                pluginMetadata = PluginMetadata.builder()
                    .pluginId(metadata.getString("pluginId"))
                    .version(metadata.getString("version"))
                    .author(metadata.getString("author"))
                    .build();
            }

            return StorageMetadata.builder()
                .fileId(fileId)
                .fileName(file.getFilename())
                .contentType(file.getMetadata() != null ? file.getMetadata().getString("_contentType") : null)
                .fileSize(file.getLength())
                .uploadedAt(file.getUploadDate() != null ?
                    LocalDateTime.ofInstant(file.getUploadDate().toInstant(), ZoneId.systemDefault()) : null)
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
            GridFSFile file = gridFsTemplate.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(fileId)))
            );
            return file != null;
        } catch (Exception e) {
            log.error("Failed to check file existence: {}", fileId, e);
            return false;
        }
    }
}
