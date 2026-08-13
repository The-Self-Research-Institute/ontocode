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

    private Integer stars;

    private String review;

    private String merits;

    private String demerits;

    private Boolean recommended;
}
