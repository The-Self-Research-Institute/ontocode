package self.research.ontology.plugins.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.plugins.dto.PluginDTO;
import self.research.ontology.plugins.dto.PluginVersionDTO;
import self.research.ontology.plugins.dto.PublishPluginRequest;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginVersion;
import self.research.ontology.plugins.repository.PluginRepository;
import self.research.ontology.plugins.repository.PluginVersionRepository;
import self.research.ontology.plugins.storage.PluginMetadata;
import self.research.ontology.plugins.storage.PluginStorageService;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginService {

    private final PluginRepository pluginRepository;
    private final PluginVersionRepository versionRepository;
    private final PluginStorageService storageService;

    public Page<PluginDTO> browsePlugins(int page, int size, String category, String sort) {
        Pageable pageable = createPageable(page, size, sort);

        Page<Plugin> plugins;
        if (category != null && !category.isEmpty()) {
            plugins = pluginRepository.findByCategory(category, pageable);
        } else {
            plugins = pluginRepository.findActivePlugins(pageable);
        }

        return plugins.map(this::toDTO);
    }

    public Page<PluginDTO> searchPlugins(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "totalDownloads"));

        String safeQuery = query == null ? "" : query.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
        Page<Plugin> plugins = pluginRepository.searchPlugins(safeQuery, pageable);
        return plugins.map(this::toDTO);
    }

    public PluginDTO getPluginDetails(String pluginId) {
        Plugin plugin = pluginRepository.findByPluginId(pluginId)
            .orElseThrow(() -> new PluginNotFoundException("Plugin not found: " + pluginId));
        return toDTO(plugin);
    }

    public List<PluginVersionDTO> getPluginVersions(String pluginId) {
        return versionRepository.findByPluginIdOrderByPublishedAtDesc(pluginId)
            .stream()
            .map(this::toVersionDTO)
            .collect(Collectors.toList());
    }

    public InputStream downloadPlugin(String pluginId, String version) {
        PluginVersion pluginVersion = versionRepository.findByPluginIdAndVersion(pluginId, version)
            .orElseThrow(() -> new PluginNotFoundException("Plugin version not found: " + pluginId + "@" + version));

        pluginVersion.setDownloads((pluginVersion.getDownloads() != null ? pluginVersion.getDownloads() : 0L) + 1);
        versionRepository.save(pluginVersion);

        pluginRepository.findByPluginId(pluginId).ifPresent(plugin -> {
            plugin.setTotalDownloads((plugin.getTotalDownloads() != null ? plugin.getTotalDownloads() : 0L) + 1);
            pluginRepository.save(plugin);
        });

        return storageService.downloadPlugin(pluginVersion.getVsixFileId());
    }

    @Transactional
    public PluginDTO publishPlugin(PublishPluginRequest request, MultipartFile vsixFile, String authorEmail) {
        try {
            log.info("Publishing plugin: {} version: {}", request.getPluginId(), request.getVersion());

            PluginMetadata metadata = PluginMetadata.builder()
                .pluginId(request.getPluginId())
                .version(request.getVersion())
                .author(authorEmail)
                .fileSize(vsixFile.getSize())
                .build();

            String fileId = storageService.uploadPlugin(
                vsixFile.getInputStream(),
                vsixFile.getOriginalFilename(),
                vsixFile.getContentType(),
                metadata
            );

            Plugin plugin = pluginRepository.findByPluginId(request.getPluginId())
                .orElse(Plugin.builder()
                    .pluginId(request.getPluginId())
                    .createdAt(LocalDateTime.now())
                    .totalDownloads(0L)
                    .verified(false)
                    .deprecated(false)
                    .build());

            plugin.setName(request.getName());
            plugin.setDescription(request.getDescription());
            plugin.setAuthorEmail(authorEmail);
            plugin.setLatestVersion(request.getVersion());
            plugin.setCategory(request.getCategory());
            plugin.setKeywords(request.getKeywords());
            plugin.setLicense(request.getLicense());
            plugin.setRepository(request.getRepository());
            plugin.setHomepage(request.getHomepage());
            plugin.setIcon(request.getIcon());
            plugin.setScreenshots(request.getScreenshots());
            plugin.setUpdatedAt(LocalDateTime.now());

            pluginRepository.save(plugin);

            PluginVersion version = versionRepository.findByPluginIdAndVersion(request.getPluginId(), request.getVersion())
                .orElse(PluginVersion.builder()
                    .pluginId(request.getPluginId())
                    .version(request.getVersion())
                    .downloads(0L)
                    .publishedAt(LocalDateTime.now())
                    .build());

            version.setChangelog(request.getChangelog());
            version.setVsixFileId(fileId);
            version.setFileSize(vsixFile.getSize());
            version.setDependencies(request.getDependencies());
            version.setEngines(request.getEngines());
            version.setEntryPoint(request.getEntryPoint());
            version.setDeprecated(false);

            versionRepository.save(version);

            log.info("Plugin published successfully: {} version: {}", request.getPluginId(), request.getVersion());
            return toDTO(plugin);

        } catch (Exception e) {
            log.error("Failed to publish plugin: {}", request.getPluginId(), e);
            throw new PluginPublishException("Failed to publish plugin", e);
        }
    }

    private Pageable createPageable(int page, int size, String sort) {
        Sort sorting = switch (sort != null ? sort : "downloads") {
            case "name" -> Sort.by(Sort.Direction.ASC, "name");
            case "date" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "rating" -> Sort.by(Sort.Direction.DESC, "averageRating");
            default -> Sort.by(Sort.Direction.DESC, "totalDownloads");
        };
        return PageRequest.of(page, size, sorting);
    }

    private PluginDTO toDTO(Plugin plugin) {
        return PluginDTO.builder()
            .pluginId(plugin.getPluginId())
            .name(plugin.getName())
            .description(plugin.getDescription())
            .author(plugin.getAuthor())
            .latestVersion(plugin.getLatestVersion())
            .category(plugin.getCategory())
            .keywords(plugin.getKeywords())
            .license(plugin.getLicense())
            .repository(plugin.getRepository())
            .homepage(plugin.getHomepage())
            .icon(plugin.getIcon())
            .screenshots(plugin.getScreenshots())
            .totalDownloads(plugin.getTotalDownloads())
            .averageRating(plugin.getAverageRating())
            .reviewCount(plugin.getReviewCount())
            .verified(plugin.getVerified())
            .deprecated(plugin.getDeprecated())
            .createdAt(plugin.getCreatedAt())
            .updatedAt(plugin.getUpdatedAt())
            .build();
    }

    private PluginVersionDTO toVersionDTO(PluginVersion version) {
        return PluginVersionDTO.builder()
            .version(version.getVersion())
            .changelog(version.getChangelog())
            .fileSize(version.getFileSize())
            .dependencies(version.getDependencies())
            .engines(version.getEngines())
            .deprecated(version.getDeprecated())
            .deprecationMessage(version.getDeprecationMessage())
            .downloads(version.getDownloads())
            .publishedAt(version.getPublishedAt())
            .build();
    }
}
