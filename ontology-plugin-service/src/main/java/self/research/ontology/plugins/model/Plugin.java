package self.research.ontology.plugins.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plugins")
public class Plugin {

    @Id
    private String id;

    @Indexed(unique = true)
    private String pluginId;  // e.g., "ontocode-theme-pack"

    private String name;  // Display name
    private String description;
    private String author;
    private String authorEmail;

    private String latestVersion;  // Current stable version
    private String category;  // e.g., "Visualization", "Editor", "Utility"
    private List<String> keywords;

    private String license;  // e.g., "MIT", "Apache-2.0"
    private String repository;  // Git repository URL
    private String homepage;  // Plugin homepage URL
    private String icon;  // Icon URL or base64
    private List<String> screenshots;  // Screenshot URLs

    private Long totalDownloads;
    private Double averageRating;  // 0.0 - 5.0
    private Integer reviewCount;

    private Boolean verified;  // Official/verified plugin
    private Boolean deprecated;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
