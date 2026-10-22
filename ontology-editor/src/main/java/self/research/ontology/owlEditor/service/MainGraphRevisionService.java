package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class MainGraphRevisionService {

    public record MainGraphChanged(String projectId) {}

    private final ProjectMetadataService metadataService;

    @Autowired(required = false)
    private ApplicationEventPublisher publisher;

    public MainGraphRevisionService(ProjectMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public long getRevision(String projectId) {
        return metadataService.getMainGraphRevision(projectId);
    }

    public long incrementRevision(String projectId) {
        long revision = metadataService.incrementMainGraphRevision(projectId);
        if (publisher != null) {
            publisher.publishEvent(new MainGraphChanged(projectId));
        }
        return revision;
    }
}
