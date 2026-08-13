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

    private String pluginId;
    private String version;
    private String changelog;

    private String vsixFileId;
    private Long fileSize;

    private Map<String, String> dependencies;
    private Map<String, String> engines;

    private String entryPoint;
    private Boolean deprecated;
    private String deprecationMessage;

    private Long downloads;
    private LocalDateTime publishedAt;
}
