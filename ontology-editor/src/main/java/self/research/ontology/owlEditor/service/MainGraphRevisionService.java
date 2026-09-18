package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MainGraphRevisionService {

    private static final Logger log = LoggerFactory.getLogger(MainGraphRevisionService.class);

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
        log.info("[REVISION] incrementRevision called for project={}", projectId);
        try {
            long result = metadataService.incrementMainGraphRevision(projectId);
            log.info("[REVISION] incrementRevision result for project={} -> {}", projectId, result);
            return result;
        } catch (Exception e) {
            log.error("[REVISION] incrementRevision threw for project={}", projectId, e);
            throw e;
        }
    }
}
