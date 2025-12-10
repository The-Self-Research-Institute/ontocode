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
    private String pluginId;  // Plugin being rated

    @Indexed
    private String userId;  // User who gave the rating

    private String username;  // Display name of user

    private Integer stars;  // 1-5 stars

    private String review;  // Optional review text (merits/demerits)

    private String merits;  // What user likes about the plugin (optional)

    private String demerits;  // What user dislikes about the plugin (optional)

    private Boolean recommended;  // Would recommend this plugin

    private Integer helpfulCount;  // How many users found this review helpful

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
