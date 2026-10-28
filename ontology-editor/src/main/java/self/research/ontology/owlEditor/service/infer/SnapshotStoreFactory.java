package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.repository.Repository;

public interface SnapshotStoreFactory {

    Repository create(long expectedTriples);
}
