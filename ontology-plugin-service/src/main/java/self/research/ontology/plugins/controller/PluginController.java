package self.research.ontology.plugins.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.plugins.dto.PluginDTO;
import self.research.ontology.plugins.dto.PluginStatsResponse;
import self.research.ontology.plugins.dto.PluginVersionDTO;
import self.research.ontology.plugins.dto.PublishPluginRequest;
import self.research.ontology.plugins.dto.RatingRequest;
import self.research.ontology.plugins.dto.RatingResponse;
import self.research.ontology.plugins.model.PluginUserInstall;
import self.research.ontology.plugins.service.PluginInstallTrackingService;
import self.research.ontology.plugins.service.PluginRatingService;
import self.research.ontology.plugins.service.PluginService;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PluginController {

    private final PluginService pluginService;
    private final PluginRatingService ratingService;
    private final PluginInstallTrackingService trackingService;

    @GetMapping
    public ResponseEntity<Page<PluginDTO>> browsePlugins(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "downloads") String sort) {

        log.info("Browse plugins - page: {}, size: {}, category: {}, sort: {}", page, size, category, sort);
        Page<PluginDTO> plugins = pluginService.browsePlugins(page, size, category, sort);
        return ResponseEntity.ok(plugins);
    }

    @GetMapping("/search")
    public ResponseEntity<Page<PluginDTO>> searchPlugins(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Search plugins - query: {}, page: {}, size: {}", query, page, size);
        Page<PluginDTO> plugins = pluginService.searchPlugins(query, page, size);
        return ResponseEntity.ok(plugins);
    }

    @GetMapping("/{pluginId}")
    public ResponseEntity<PluginDTO> getPluginDetails(@PathVariable String pluginId) {
        log.info("Get plugin details - pluginId: {}", pluginId);
        PluginDTO plugin = pluginService.getPluginDetails(pluginId);
        return ResponseEntity.ok(plugin);
    }

    @GetMapping("/{pluginId}/versions")
    public ResponseEntity<List<PluginVersionDTO>> getPluginVersions(@PathVariable String pluginId) {
        log.info("Get plugin versions - pluginId: {}", pluginId);
        List<PluginVersionDTO> versions = pluginService.getPluginVersions(pluginId);
        return ResponseEntity.ok(versions);
    }

    @GetMapping("/{pluginId}/download")
    public ResponseEntity<InputStreamResource> downloadPlugin(
            @PathVariable String pluginId,
            @RequestParam(required = false) String version) {

        log.info("Download plugin - pluginId: {}, version: {}", pluginId, version);

        // If version not specified, use latest
        if (version == null || version.isEmpty()) {
            PluginDTO plugin = pluginService.getPluginDetails(pluginId);
            version = plugin.getLatestVersion();
        }

        InputStream fileStream = pluginService.downloadPlugin(pluginId, version);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/javascript");
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

        return ResponseEntity.ok()
            .headers(headers)
            .contentType(MediaType.valueOf("application/javascript"))
            .body(new InputStreamResource(fileStream));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginDTO> publishPlugin(
            @Valid @RequestPart("metadata") PublishPluginRequest request,
            @RequestPart("vsixFile") MultipartFile vsixFile,
            Authentication authentication) {

        log.info("Publish plugin request - pluginId: {}, version: {}", request.getPluginId(), request.getVersion());

        // Validate file
        if (vsixFile.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String filename = vsixFile.getOriginalFilename();
        // Accept both .vsix and .js files for development (plugins can be UMD bundles)
        if (filename == null || (!filename.endsWith(".vsix") && !filename.endsWith(".js"))) {
            log.error("Invalid file type. Must be .vsix or .js file, got: {}", filename);
            return ResponseEntity.badRequest().build();
        }

        // Get user email from JWT or use default for development
        String authorEmail = (authentication != null) ? authentication.getName() : "admin@ontocode.dev";

        PluginDTO plugin = pluginService.publishPlugin(request, vsixFile, authorEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(plugin);
    }

    // ==================== RATING ENDPOINTS ====================

    /**
     * Rate a plugin with stars and optional review
     */
    @PostMapping("/{pluginId}/rate")
    public ResponseEntity<RatingResponse> ratePlugin(
            @PathVariable String pluginId,
            @Valid @RequestBody RatingRequest request,
            Authentication authentication) {

        // Handle anonymous ratings for development
        String userId = authentication != null ? authentication.getName() : "anonymous";
        String username = authentication != null ? authentication.getName() : "Anonymous User";
        
        request.setPluginId(pluginId);
        RatingResponse rating = ratingService.ratePlugin(userId, username, request);
        
        log.info("User {} rated plugin {} with {} stars", userId, pluginId, request.getStars());
        return ResponseEntity.ok(rating);
    }

    /**
     * Get all ratings for a plugin
     */
    @GetMapping("/{pluginId}/ratings")
    public ResponseEntity<List<RatingResponse>> getPluginRatings(@PathVariable String pluginId) {
        List<RatingResponse> ratings = ratingService.getPluginRatings(pluginId);
        return ResponseEntity.ok(ratings);
    }

    /**
     * Get current user's rating for a plugin
     */
    @GetMapping("/{pluginId}/my-rating")
    public ResponseEntity<RatingResponse> getMyRating(
            @PathVariable String pluginId,
            Authentication authentication) {
        
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = authentication.getName();
        RatingResponse rating = ratingService.getUserRating(pluginId, userId);
        
        if (rating == null) {
            return ResponseEntity.noContent().build();
        }
        
        return ResponseEntity.ok(rating);
    }

    /**
     * Delete user's rating
     */
    @DeleteMapping("/{pluginId}/my-rating")
    public ResponseEntity<Void> deleteMyRating(
            @PathVariable String pluginId,
            Authentication authentication) {
        
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = authentication.getName();
        ratingService.deleteRating(pluginId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Mark a review as helpful
     */
    @PostMapping("/ratings/{ratingId}/helpful")
    public ResponseEntity<Void> markReviewHelpful(@PathVariable String ratingId) {
        ratingService.markReviewHelpful(ratingId);
        return ResponseEntity.ok().build();
    }

    /**
     * Get rating statistics for a plugin
     */
    @GetMapping("/{pluginId}/rating-stats")
    public ResponseEntity<Map<String, Object>> getRatingStats(@PathVariable String pluginId) {
        Map<String, Object> stats = ratingService.getRatingStats(pluginId);
        return ResponseEntity.ok(stats);
    }

    // ==================== INSTALLATION TRACKING ENDPOINTS ====================

    /**
     * Track plugin installation
     */
    @PostMapping("/{pluginId}/install")
    public ResponseEntity<Void> trackInstall(
            @PathVariable String pluginId,
            @RequestParam(required = false) String version,
            Authentication authentication) {
        
        String userId = authentication != null ? authentication.getName() : "anonymous";
        String username = authentication != null ? authentication.getName() : "Anonymous User";
        
        // Use latest version if not specified
        if (version == null || version.isEmpty()) {
            PluginDTO plugin = pluginService.getPluginDetails(pluginId);
            version = plugin.getLatestVersion();
        }
        
        trackingService.trackInstall(pluginId, userId, username, version);
        log.info("Tracked installation of plugin {} by user {}", pluginId, userId);
        
        return ResponseEntity.ok().build();
    }

    /**
     * Track plugin uninstallation
     */
    @PostMapping("/{pluginId}/uninstall")
    public ResponseEntity<Void> trackUninstall(
            @PathVariable String pluginId,
            Authentication authentication) {
        
        String userId = authentication != null ? authentication.getName() : "anonymous";
        trackingService.trackUninstall(pluginId, userId);
        log.info("Tracked uninstallation of plugin {} by user {}", pluginId, userId);
        
        return ResponseEntity.ok().build();
    }

    /**
     * Get comprehensive stats for a plugin (installs, ratings, etc.)
     */
    @GetMapping("/{pluginId}/stats")
    public ResponseEntity<PluginStatsResponse> getPluginStats(@PathVariable String pluginId) {
        PluginStatsResponse stats = trackingService.getPluginStats(pluginId);
        return ResponseEntity.ok(stats);
    }

    /**
     * Get user's installation info for a plugin
     */
    @GetMapping("/{pluginId}/my-install")
    public ResponseEntity<PluginUserInstall> getMyInstallInfo(
            @PathVariable String pluginId,
            Authentication authentication) {
        
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = authentication.getName();
        PluginUserInstall install = trackingService.getUserInstallInfo(pluginId, userId);
        
        if (install == null) {
            return ResponseEntity.noContent().build();
        }
        
        return ResponseEntity.ok(install);
    }

    /**
     * Get all plugins installed by current user
     */
    @GetMapping("/my-installs")
    public ResponseEntity<List<PluginUserInstall>> getMyInstalledPlugins(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String userId = authentication.getName();
        List<PluginUserInstall> installs = trackingService.getUserInstalledPlugins(userId);
        return ResponseEntity.ok(installs);
    }

    /**
     * Check if current user has installed a plugin
     */
    @GetMapping("/{pluginId}/is-installed")
    public ResponseEntity<Map<String, Boolean>> isPluginInstalled(
            @PathVariable String pluginId,
            Authentication authentication) {
        
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = authentication.getName();
        boolean isInstalled = trackingService.isPluginInstalledByUser(pluginId, userId);
        
        return ResponseEntity.ok(Map.of("isInstalled", isInstalled));
    }

    /**
     * Get user's install count for a plugin
     */
    @GetMapping("/{pluginId}/my-install-count")
    public ResponseEntity<Map<String, Integer>> getMyInstallCount(
            @PathVariable String pluginId,
            Authentication authentication) {
        
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = authentication.getName();
        Integer count = trackingService.getUserInstallCount(pluginId, userId);
        
        return ResponseEntity.ok(Map.of("installCount", count));
    }

    // ==================== EXCEPTION HANDLERS ====================

    @ExceptionHandler(self.research.ontology.plugins.service.PluginNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePluginNotFound(
            self.research.ontology.plugins.service.PluginNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(self.research.ontology.plugins.service.PluginPublishException.class)
    public ResponseEntity<ErrorResponse> handlePublishError(
            self.research.ontology.plugins.service.PluginPublishException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(ex.getMessage()));
    }

    record ErrorResponse(String message) {}
}
