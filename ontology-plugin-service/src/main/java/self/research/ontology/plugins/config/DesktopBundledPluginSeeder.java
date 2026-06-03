package self.research.ontology.plugins.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginVersion;
import self.research.ontology.plugins.repository.PluginRepository;
import self.research.ontology.plugins.repository.PluginVersionRepository;
import self.research.ontology.plugins.storage.PluginMetadata;
import self.research.ontology.plugins.storage.PluginStorageService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Seeds bundled plugin JS bundles from the installer into MongoDB/GridFS on first
 * desktop startup so /api/plugins/{id}/download works offline.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
@RequiredArgsConstructor
public class DesktopBundledPluginSeeder {

    private final PluginRepository pluginRepository;
    private final PluginVersionRepository versionRepository;
    private final PluginStorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${ontocode.desktop.plugins.bundled-dir:}")
    private String bundledDir;

    @EventListener(ApplicationReadyEvent.class)
    public void seedBundledPlugins() {
        if (bundledDir == null || bundledDir.isBlank()) {
            log.debug("[Desktop] No ontocode.desktop.plugins.bundled-dir — skipping bundled plugin seed");
            return;
        }

        Path root = Path.of(bundledDir);
        Path manifest = root.resolve("plugins-manifest.json");
        if (!Files.isDirectory(root) || !Files.isRegularFile(manifest)) {
            log.warn("[Desktop] Bundled plugins dir missing or no manifest: {}", root);
            return;
        }

        try {
            JsonNode entries = objectMapper.readTree(Files.readString(manifest));
            if (!entries.isArray()) {
                log.warn("[Desktop] Invalid plugins-manifest.json (expected array)");
                return;
            }

            int seeded = 0;
            for (JsonNode entry : entries) {
                if (seedOne(root, entry)) {
                    seeded++;
                }
            }
            if (seeded > 0) {
                log.info("[Desktop] Seeded {} bundled plugin(s) from {}", seeded, root);
            }
        } catch (Exception e) {
            log.error("[Desktop] Bundled plugin seed failed: {}", e.getMessage(), e);
        }
    }

    private boolean seedOne(Path root, JsonNode entry) {
        String pluginId = text(entry, "pluginId");
        String version = text(entry, "version");
        if (pluginId == null || version == null) {
            return false;
        }

        Path bundle = root.resolve(pluginId).resolve("index.js");
        if (!Files.isRegularFile(bundle)) {
            log.warn("[Desktop] Bundled bundle missing for {}: {}", pluginId, bundle);
            return false;
        }

        Optional<PluginVersion> existingVersion =
            versionRepository.findByPluginIdAndVersion(pluginId, version);
        if (existingVersion.isPresent()) {
            return false;
        }

        try (InputStream in = Files.newInputStream(bundle)) {
            String fileName = pluginId + "-" + version + ".js";
            PluginMetadata meta = PluginMetadata.builder()
                .pluginId(pluginId)
                .version(version)
                .author("OntoCode Team")
                .fileSize(Files.size(bundle))
                .build();

            String fileId = storageService.uploadPlugin(in, fileName, "application/javascript", meta);

            LocalDateTime now = LocalDateTime.now();
            Plugin plugin = pluginRepository.findByPluginId(pluginId).orElse(Plugin.builder()
                .pluginId(pluginId)
                .createdAt(now)
                .totalDownloads(0L)
                .verified(true)
                .deprecated(false)
                .build());

            plugin.setName(text(entry, "name"));
            plugin.setDescription(text(entry, "description"));
            plugin.setAuthor("OntoCode Team");
            plugin.setAuthorEmail("admin@ontocode.local");
            plugin.setLatestVersion(version);
            plugin.setCategory(text(entry, "category"));
            plugin.setKeywords(readKeywords(entry.get("keywords")));
            plugin.setUpdatedAt(now);
            pluginRepository.save(plugin);

            PluginVersion pv = PluginVersion.builder()
                .pluginId(pluginId)
                .version(version)
                .vsixFileId(fileId)
                .fileSize(Files.size(bundle))
                .changelog("Bundled with OntoCode Desktop")
                .downloads(0L)
                .publishedAt(now)
                .build();
            versionRepository.save(pv);

            log.info("[Desktop] Seeded bundled plugin {}@{}", pluginId, version);
            return true;
        } catch (Exception e) {
            log.warn("[Desktop] Failed to seed {}: {}", pluginId, e.getMessage());
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static List<String> readKeywords(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Iterator<JsonNode> it = node.elements();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next().asText());
        }
        return out;
    }
}
