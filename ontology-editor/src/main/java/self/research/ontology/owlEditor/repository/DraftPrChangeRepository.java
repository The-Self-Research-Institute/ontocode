package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.DraftPrChange;

import java.util.List;

public interface DraftPrChangeRepository {

    List<DraftPrChange> saveAll(List<DraftPrChange> rows);

    List<DraftPrChange> findByPrId(String prId);

    List<DraftPrChange> findByPrIdAndSnapshotKey(String prId, String snapshotKey);

    void deleteByPrIdAndSnapshotKey(String prId, String snapshotKey);

    void deleteByPrId(String prId);
}
