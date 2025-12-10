package self.research.ontology.plugins.storage;

import java.io.InputStream;

/**
 * Abstraction layer for plugin file storage.
 * Allows easy migration from GridFS (Phase 1) to S3+CDN (Phase 2) without code changes.
 */
public interface PluginStorageService {

    /**
     * Upload a plugin VSIX file
     * @param fileStream InputStream of the VSIX file
     * @param fileName Original filename
     * @param contentType MIME type
     * @param metadata Additional metadata (plugin ID, version, etc.)
     * @return File identifier for retrieval
     */
    String uploadPlugin(InputStream fileStream, String fileName, String contentType, PluginMetadata metadata);

    /**
     * Download a plugin VSIX file
     * @param fileId File identifier returned from uploadPlugin
     * @return InputStream to read the file
     */
    InputStream downloadPlugin(String fileId);

    /**
     * Delete a plugin VSIX file
     * @param fileId File identifier to delete
     */
    void deletePlugin(String fileId);

    /**
     * Get file metadata
     * @param fileId File identifier
     * @return File metadata including size, upload date, etc.
     */
    StorageMetadata getMetadata(String fileId);

    /**
     * Check if file exists
     * @param fileId File identifier
     * @return true if file exists
     */
    boolean exists(String fileId);
}
