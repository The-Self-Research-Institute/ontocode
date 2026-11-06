package self.research.ontology.owlEditor.repository;

import java.time.Instant;

public interface PublicGraphVersionRepository {

    long increment(String projectId, Instant now);

    long read(String projectId);
}
