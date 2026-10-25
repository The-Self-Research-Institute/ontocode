package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DraftPrChange;

import java.util.List;

@Repository
public interface DraftPrChangeRepository extends MongoRepository<DraftPrChange, String> {

    List<DraftPrChange> findByPrId(String prId);

    List<DraftPrChange> findByPrIdAndSnapshotKey(String prId, String snapshotKey);

    void deleteByPrIdAndSnapshotKey(String prId, String snapshotKey);

    void deleteByPrId(String prId);
}
