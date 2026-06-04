package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;

public interface HierarchySnapshotRepository extends MongoRepository<HierarchySnapshotDoc, String> {
}
