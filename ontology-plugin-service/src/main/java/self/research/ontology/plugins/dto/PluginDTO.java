package self.research.ontology.plugins.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginDTO {
    private String pluginId;
    private String name;
    private String description;
    private String author;
    private String latestVersion;
    private String category;
    private List<String> keywords;
    private String license;
    private String repository;
    private String homepage;
    private String icon;
    private List<String> screenshots;
    private Long totalDownloads;
    private Double averageRating;
    private Integer reviewCount;
    private Boolean verified;
    private Boolean deprecated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
