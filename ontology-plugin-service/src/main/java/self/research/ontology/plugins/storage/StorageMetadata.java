package self.research.ontology.plugins.storage;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class StorageMetadata {
    private String fileId;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private LocalDateTime uploadedAt;
    private PluginMetadata pluginMetadata;
}
