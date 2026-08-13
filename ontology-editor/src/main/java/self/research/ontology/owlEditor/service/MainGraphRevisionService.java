package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

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
        return metadataService.incrementMainGraphRevision(projectId);
    }
}
