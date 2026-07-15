package self.research.ontology.plugins.storage;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PluginMetadata {
    private String pluginId;
    private String version;
    private String author;
    private Long fileSize;
}
