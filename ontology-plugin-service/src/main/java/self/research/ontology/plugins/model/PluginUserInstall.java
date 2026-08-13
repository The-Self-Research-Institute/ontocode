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
    private String pluginId;

    @Indexed
    private String userId;

    private String username;

    private String version;

    private Boolean isActive;

    private Integer totalInstalls;

    private LocalDateTime firstInstalledAt;

    private LocalDateTime lastInstalledAt;

    private LocalDateTime lastUninstalledAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
