package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Monotonic revision counter for the project main graph (incremented on every publish / direct apply).
 */
@Service
public class MainGraphRevisionService {

    private static final String META_KEY = "mainGraphRevision";

    private final ProjectMetadataService metadataService;

    public MainGraphRevisionService(ProjectMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public long getRevision(String projectId) {
        return metadataService.readMeta(projectId)
                .map(meta -> meta.get(META_KEY))
                .filter(Number.class::isInstance)
                .map(n -> ((Number) n).longValue())
                .orElse(0L);
    }

    public long incrementRevision(String projectId) {
        long next = getRevision(projectId) + 1;
        Map<String, Object> patch = new HashMap<>();
        metadataService.readMeta(projectId).ifPresent(patch::putAll);
        patch.put(META_KEY, next);
        metadataService.writeMeta(projectId, patch);
        return next;
    }
}
