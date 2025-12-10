package self.research.ontology.plugins.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import self.research.ontology.plugins.dto.PluginStatsResponse;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginRating;
import self.research.ontology.plugins.model.PluginUserInstall;
import self.research.ontology.plugins.repository.PluginRatingRepository;
import self.research.ontology.plugins.repository.PluginRepository;
import self.research.ontology.plugins.repository.PluginUserInstallRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginInstallTrackingService {

    private final PluginUserInstallRepository installRepository;
    private final PluginRepository pluginRepository;
    private final PluginRatingRepository ratingRepository;

    /**
     * Track plugin installation by user
     */
    @Transactional
    public void trackInstall(String pluginId, String userId, String username, String version) {
        PluginUserInstall install = installRepository.findByPluginIdAndUserId(pluginId, userId)
                .orElse(PluginUserInstall.builder()
                        .pluginId(pluginId)
                        .userId(userId)
                        .username(username)
                        .totalInstalls(0)
                        .firstInstalledAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .build());

        // Update install record
        install.setVersion(version);
        install.setIsActive(true);
        install.setTotalInstalls(install.getTotalInstalls() + 1);
        install.setLastInstalledAt(LocalDateTime.now());
        install.setUpdatedAt(LocalDateTime.now());

        installRepository.save(install);

        // Update plugin's total downloads
        updatePluginDownloadCount(pluginId);

        log.info("Tracked installation of plugin {} by user {} (version {})", pluginId, userId, version);
    }

    /**
     * Track plugin uninstallation
     */
    @Transactional
    public void trackUninstall(String pluginId, String userId) {
        installRepository.findByPluginIdAndUserId(pluginId, userId)
                .ifPresent(install -> {
                    install.setIsActive(false);
                    install.setLastUninstalledAt(LocalDateTime.now());
                    install.setUpdatedAt(LocalDateTime.now());
                    installRepository.save(install);
                    
                    log.info("Tracked uninstallation of plugin {} by user {}", pluginId, userId);
                });
    }

    /**
     * Get installation statistics for a plugin
     */
    public PluginStatsResponse getPluginStats(String pluginId) {
        // Get install data
        Long totalInstalls = installRepository.countByPluginId(pluginId);
        Long activeInstalls = installRepository.countByPluginIdAndIsActive(pluginId, true);

        // Get rating data
        List<PluginRating> ratings = ratingRepository.findByPluginId(pluginId);
        
        double averageRating = ratings.stream()
                .mapToInt(PluginRating::getStars)
                .average()
                .orElse(0.0);

        Map<Integer, Long> distribution = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            int stars = i;
            long count = ratings.stream().filter(r -> r.getStars() == stars).count();
            distribution.put(stars, count);
        }

        long recommendedCount = ratings.stream()
                .filter(r -> Boolean.TRUE.equals(r.getRecommended()))
                .count();

        int totalReviews = (int) ratings.stream()
                .filter(r -> r.getReview() != null && !r.getReview().isEmpty())
                .count();

        // Get plugin total downloads
        Plugin plugin = pluginRepository.findByPluginId(pluginId).orElse(null);
        Long totalDownloads = plugin != null ? plugin.getTotalDownloads() : 0L;

        return PluginStatsResponse.builder()
                .pluginId(pluginId)
                .totalInstalls(totalInstalls)
                .activeInstalls(activeInstalls)
                .totalDownloads(totalDownloads)
                .averageRating(Math.round(averageRating * 10.0) / 10.0)
                .totalRatings(ratings.size())
                .ratingDistribution(distribution)
                .recommendedCount(recommendedCount)
                .totalReviews(totalReviews)
                .build();
    }

    /**
     * Get user's installation history for a plugin
     */
    public PluginUserInstall getUserInstallInfo(String pluginId, String userId) {
        return installRepository.findByPluginIdAndUserId(pluginId, userId).orElse(null);
    }

    /**
     * Get all plugins installed by a user
     */
    public List<PluginUserInstall> getUserInstalledPlugins(String userId) {
        return installRepository.findByUserId(userId);
    }

    /**
     * Check if user has installed a plugin
     */
    public boolean isPluginInstalledByUser(String pluginId, String userId) {
        return installRepository.findByPluginIdAndUserId(pluginId, userId)
                .map(PluginUserInstall::getIsActive)
                .orElse(false);
    }

    /**
     * Get installation count for a specific user and plugin
     */
    public Integer getUserInstallCount(String pluginId, String userId) {
        return installRepository.findByPluginIdAndUserId(pluginId, userId)
                .map(PluginUserInstall::getTotalInstalls)
                .orElse(0);
    }

    /**
     * Update plugin's total download count
     */
    private void updatePluginDownloadCount(String pluginId) {
        pluginRepository.findByPluginId(pluginId).ifPresent(plugin -> {
            Long totalDownloads = installRepository.countByPluginId(pluginId);
            plugin.setTotalDownloads(totalDownloads);
            plugin.setUpdatedAt(LocalDateTime.now());
            pluginRepository.save(plugin);
        });
    }
}
