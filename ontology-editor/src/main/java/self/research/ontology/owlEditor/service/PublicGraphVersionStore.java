package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.repository.PublicGraphVersionRepository;

import java.time.Instant;

@Service
public class PublicGraphVersionStore {

    private final PublicGraphVersionRepository repository;

    public PublicGraphVersionStore(PublicGraphVersionRepository repository) {
        this.repository = repository;
    }

    public long increment(String projectId) {
        return repository.increment(projectId, Instant.now());
    }

    public long read(String projectId) {
        return repository.read(projectId);
    }
}
