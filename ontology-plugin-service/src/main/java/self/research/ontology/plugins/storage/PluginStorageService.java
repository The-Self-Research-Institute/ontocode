package self.research.ontology.plugins.storage;

import java.io.InputStream;

public interface PluginStorageService {

    String uploadPlugin(InputStream fileStream, String fileName, String contentType, PluginMetadata metadata);

    InputStream downloadPlugin(String fileId);

    void deletePlugin(String fileId);

    StorageMetadata getMetadata(String fileId);

    boolean exists(String fileId);
}
