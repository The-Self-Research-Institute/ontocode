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

    private String userId;
    private String pluginId;
    private String installedVersion;

    private Boolean autoUpdate;
    private Boolean enabled;

    private LocalDateTime installedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;
}
