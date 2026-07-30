package self.research.ontology.owlEditor.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records open timings for desktop benchmarking (file size, parse ms, total ms).
 */
@Service
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopOpenMetricsService {

    private final ConcurrentHashMap<String, Map<String, Object>> byProject = new ConcurrentHashMap<>();

    public void recordOwlApiLoad(String projectId,
                                 String fileName,
                                 long fileSizeBytes,
                                 long parseMs,
                                 String sourcePath) {
        Map<String, Object> m = base(projectId);
        m.put("fileName", fileName);
        m.put("fileSizeBytes", fileSizeBytes);
        m.put("fileSizeMb", roundMb(fileSizeBytes));
        m.put("owlApiParseMs", parseMs);
        m.put("sourcePath", sourcePath);
        m.put("recordedAt", Instant.now().toString());
    }

    public void recordImportComplete(String projectId,
                                     long totalMs,
                                     boolean owlApiFirst,
                                     boolean fusekiSynced) {
        Map<String, Object> m = base(projectId);
        m.put("importTotalMs", totalMs);
        m.put("owlApiFirst", owlApiFirst);
        m.put("fusekiSynced", fusekiSynced);
        m.put("completedAt", Instant.now().toString());
    }

    public Map<String, Object> get(String projectId) {
        return new LinkedHashMap<>(byProject.getOrDefault(projectId, Map.of()));
    }

    private Map<String, Object> base(String projectId) {
        return byProject.computeIfAbsent(projectId, id -> new LinkedHashMap<>());
    }

    private static double roundMb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0 * 100.0) / 100.0;
    }
}
