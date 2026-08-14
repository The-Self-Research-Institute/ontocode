package self.research.ontology.plugins.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plugin_versions")
@CompoundIndex(name = "plugin_version_idx", def = "{'pluginId': 1, 'version': 1}", unique = true)
public class PluginVersion {

    @Id
    private String id;

    private String pluginId;  // Reference to Plugin
    private String version;  // Semantic version e.g., "1.2.3"
    private String changelog;  // Release notes

    private String vsixFileId;  // GridFS file ID
    private Long fileSize;  // File size in bytes

    private Map<String, String> dependencies;  // Plugin dependencies
    private Map<String, String> engines;  // Required OntoCode version

    private String entryPoint;  // Main file path in plugin
    private Boolean deprecated;
    private String deprecationMessage;

    private Long downloads;  // Download count for this version
    private LocalDateTime publishedAt;
}
