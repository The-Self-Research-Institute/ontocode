package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

@Service
public class MainGraphRevisionService {

    private final ProjectMetadataService metadataService;

    public MainGraphRevisionService(ProjectMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public long getRevision(String projectId) {
        return metadataService.getMainGraphRevision(projectId);
    }

    public long incrementRevision(String projectId) {
        return metadataService.incrementMainGraphRevision(projectId);
    }
}
