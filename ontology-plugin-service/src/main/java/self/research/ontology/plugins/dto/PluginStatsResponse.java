package self.research.ontology.plugins.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginStatsResponse {

    private String pluginId;

    private Long totalInstalls;

    private Long activeInstalls;

    private Long totalDownloads;

    private Double averageRating;

    private Integer totalRatings;

    private Map<Integer, Long> ratingDistribution;

    private Long recommendedCount;

    private Integer totalReviews;
}
