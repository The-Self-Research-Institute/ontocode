package self.research.ontology.plugins.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishPluginRequest {

    @NotBlank(message = "Plugin ID is required")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Plugin ID must contain only lowercase letters, numbers, and hyphens")
    private String pluginId;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Version is required")
    @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$", message = "Version must follow semantic versioning (e.g., 1.0.0)")
    private String version;

    @NotBlank(message = "Description is required")
    private String description;

    private String changelog;
    private String category;
    private List<String> keywords;
    private String license;
    private String repository;
    private String homepage;
    private String icon;
    private List<String> screenshots;

    private Map<String, String> dependencies;
    private Map<String, String> engines;
    private String entryPoint;
}
