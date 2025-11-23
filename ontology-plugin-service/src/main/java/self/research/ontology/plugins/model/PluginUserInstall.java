package self.research.ontology.plugins.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plugin_user_installs")
public class PluginUserInstall {

    @Id
    private String id;

    @Indexed
    private String pluginId;  // Plugin that was installed

    @Indexed
    private String userId;  // User who installed

    private String username;  // Display name

    private String version;  // Version installed

    private Boolean isActive;  // Currently installed (true) or uninstalled (false)

    private Integer totalInstalls;  // How many times this user installed this plugin

    private LocalDateTime firstInstalledAt;  // When user first installed

    private LocalDateTime lastInstalledAt;  // Most recent installation

    private LocalDateTime lastUninstalledAt;  // When user uninstalled (if applicable)

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
