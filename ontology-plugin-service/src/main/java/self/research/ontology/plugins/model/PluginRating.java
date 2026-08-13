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
@Document(collection = "plugin_ratings")
public class PluginRating {

    @Id
    private String id;

    @Indexed
    private String pluginId;

    @Indexed
    private String userId;

    private String username;

    private Integer stars;

    private String review;

    private String merits;

    private String demerits;

    private Boolean recommended;

    private Integer helpfulCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
