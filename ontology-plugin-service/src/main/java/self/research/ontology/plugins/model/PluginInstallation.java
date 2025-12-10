package self.research.ontology.plugins.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plugin_installations")
@CompoundIndex(name = "user_plugin_idx", def = "{'userId': 1, 'pluginId': 1}", unique = true)
public class PluginInstallation {

    @Id
    private String id;

    private String userId;  // User who installed the plugin
    private String pluginId;  // Plugin identifier
    private String installedVersion;  // Currently installed version

    private Boolean autoUpdate;  // Auto-update enabled
    private Boolean enabled;  // Plugin is enabled/disabled

    private LocalDateTime installedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;
}
