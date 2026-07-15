package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

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

    /**
     * Atomic $inc — a read-then-writeMeta round trip here would race the hierarchy
     * snapshot rebuild's own read-then-writeMeta (mergeMetaIntoProject), which can
     * clobber this counter on its write-back. See ProjectMetadataService.incrementMainGraphRevision.
     */
    public long incrementRevision(String projectId) {
        return metadataService.incrementMainGraphRevision(projectId);
    }
}
