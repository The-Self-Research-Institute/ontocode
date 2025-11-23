package self.research.ontology.plugins.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginVersionDTO {
    private String version;
    private String changelog;
    private Long fileSize;
    private Map<String, String> dependencies;
    private Map<String, String> engines;
    private Boolean deprecated;
    private String deprecationMessage;
    private Long downloads;
    private LocalDateTime publishedAt;
}
