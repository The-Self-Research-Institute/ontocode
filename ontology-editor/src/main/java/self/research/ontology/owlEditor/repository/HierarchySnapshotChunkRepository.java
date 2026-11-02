package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.owlEditor.document.HierarchySnapshotChunkDoc;

import java.util.List;

public interface HierarchySnapshotChunkRepository extends MongoRepository<HierarchySnapshotChunkDoc, String> {

    List<HierarchySnapshotChunkDoc> findBySetIdOrderByIndexAsc(String setId);

    void deleteBySetId(String setId);
}
