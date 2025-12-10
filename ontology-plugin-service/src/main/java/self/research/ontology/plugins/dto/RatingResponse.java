package self.research.ontology.plugins.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatingResponse {

    private String id;

    private String pluginId;

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
