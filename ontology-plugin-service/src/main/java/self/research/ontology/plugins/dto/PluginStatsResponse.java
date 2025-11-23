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

    private Long totalInstalls;  // Total installations across all users

    private Long activeInstalls;  // Currently active installations

    private Long totalDownloads;  // Download count

    private Double averageRating;  // Average star rating

    private Integer totalRatings;  // Number of ratings

    private Map<Integer, Long> ratingDistribution;  // Star count -> number of ratings (1->10, 2->5, etc.)

    private Long recommendedCount;  // How many users recommended this

    private Integer totalReviews;  // Number of text reviews
}
