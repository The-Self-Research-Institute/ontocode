package self.research.ontology.plugins.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatingRequest {

    private String pluginId;

    private Integer stars;  // 1-5

    private String review;  // Optional full review

    private String merits;  // Optional: What's good

    private String demerits;  // Optional: What's bad

    private Boolean recommended;  // Optional: Would recommend
}
