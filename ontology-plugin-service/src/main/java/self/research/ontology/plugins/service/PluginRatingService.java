package self.research.ontology.plugins.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import self.research.ontology.plugins.dto.PluginStatsResponse;
import self.research.ontology.plugins.dto.RatingRequest;
import self.research.ontology.plugins.dto.RatingResponse;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginRating;
import self.research.ontology.plugins.repository.PluginRatingRepository;
import self.research.ontology.plugins.repository.PluginRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginRatingService {

    private final PluginRatingRepository ratingRepository;
    private final PluginRepository pluginRepository;

    /**
     * Add or update a rating for a plugin
     */
    @Transactional
    public RatingResponse ratePlugin(String userId, String username, RatingRequest request) {
        // Validate star rating
        if (request.getStars() < 1 || request.getStars() > 5) {
            throw new IllegalArgumentException("Stars must be between 1 and 5");
        }

        // Check if user already rated this plugin
        PluginRating rating = ratingRepository.findByPluginIdAndUserId(request.getPluginId(), userId)
                .orElse(PluginRating.builder()
                        .pluginId(request.getPluginId())
                        .userId(userId)
                        .username(username)
                        .createdAt(LocalDateTime.now())
                        .helpfulCount(0)
                        .build());

        // Update rating
        rating.setStars(request.getStars());
        rating.setReview(request.getReview());
        rating.setMerits(request.getMerits());
        rating.setDemerits(request.getDemerits());
        rating.setRecommended(request.getRecommended());
        rating.setUpdatedAt(LocalDateTime.now());

        rating = ratingRepository.save(rating);

        // Update plugin's average rating
        updatePluginRating(request.getPluginId());

        log.info("User {} rated plugin {} with {} stars", userId, request.getPluginId(), request.getStars());

        return toRatingResponse(rating);
    }

    /**
     * Get all ratings for a plugin
     */
    public List<RatingResponse> getPluginRatings(String pluginId) {
        return ratingRepository.findByPluginId(pluginId).stream()
                .map(this::toRatingResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a user's rating for a plugin
     */
    public RatingResponse getUserRating(String pluginId, String userId) {
        return ratingRepository.findByPluginIdAndUserId(pluginId, userId)
                .map(this::toRatingResponse)
                .orElse(null);
    }

    /**
     * Delete a user's rating
     */
    @Transactional
    public void deleteRating(String pluginId, String userId) {
        ratingRepository.findByPluginIdAndUserId(pluginId, userId)
                .ifPresent(rating -> {
                    ratingRepository.delete(rating);
                    updatePluginRating(pluginId);
                    log.info("Deleted rating for plugin {} by user {}", pluginId, userId);
                });
    }

    /**
     * Mark a review as helpful
     */
    @Transactional
    public void markReviewHelpful(String ratingId) {
        ratingRepository.findById(ratingId).ifPresent(rating -> {
            rating.setHelpfulCount(rating.getHelpfulCount() + 1);
            ratingRepository.save(rating);
        });
    }

    /**
     * Get rating statistics for a plugin
     */
    public Map<String, Object> getRatingStats(String pluginId) {
        List<PluginRating> ratings = ratingRepository.findByPluginId(pluginId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRatings", ratings.size());

        if (ratings.isEmpty()) {
            stats.put("averageRating", 0.0);
            stats.put("distribution", Map.of(1, 0, 2, 0, 3, 0, 4, 0, 5, 0));
            return stats;
        }

        // Calculate average
        double average = ratings.stream()
                .mapToInt(PluginRating::getStars)
                .average()
                .orElse(0.0);
        stats.put("averageRating", Math.round(average * 10.0) / 10.0);

        // Calculate distribution
        Map<Integer, Long> distribution = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            int stars = i;
            long count = ratings.stream().filter(r -> r.getStars() == stars).count();
            distribution.put(stars, count);
        }
        stats.put("distribution", distribution);

        // Recommended count
        long recommendedCount = ratings.stream()
                .filter(r -> Boolean.TRUE.equals(r.getRecommended()))
                .count();
        stats.put("recommendedCount", recommendedCount);

        return stats;
    }

    /**
     * Update plugin's average rating and review count
     */
    private void updatePluginRating(String pluginId) {
        pluginRepository.findByPluginId(pluginId).ifPresent(plugin -> {
            List<PluginRating> ratings = ratingRepository.findByPluginId(pluginId);

            if (ratings.isEmpty()) {
                plugin.setAverageRating(0.0);
                plugin.setReviewCount(0);
            } else {
                double average = ratings.stream()
                        .mapToInt(PluginRating::getStars)
                        .average()
                        .orElse(0.0);
                plugin.setAverageRating(Math.round(average * 10.0) / 10.0);
                plugin.setReviewCount(ratings.size());
            }

            plugin.setUpdatedAt(LocalDateTime.now());
            pluginRepository.save(plugin);
        });
    }

    /**
     * Convert entity to response DTO
     */
    private RatingResponse toRatingResponse(PluginRating rating) {
        return RatingResponse.builder()
                .id(rating.getId())
                .pluginId(rating.getPluginId())
                .userId(rating.getUserId())
                .username(rating.getUsername())
                .stars(rating.getStars())
                .review(rating.getReview())
                .merits(rating.getMerits())
                .demerits(rating.getDemerits())
                .recommended(rating.getRecommended())
                .helpfulCount(rating.getHelpfulCount())
                .createdAt(rating.getCreatedAt())
                .updatedAt(rating.getUpdatedAt())
                .build();
    }
}
