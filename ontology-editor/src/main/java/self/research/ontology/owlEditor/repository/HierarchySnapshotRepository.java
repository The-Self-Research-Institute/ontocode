package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;

import java.util.Optional;

public interface HierarchySnapshotRepository {

    record Stored(HierarchySnapshotDoc doc, String payloadJson) {
    }

    Optional<HierarchySnapshotDoc> findById(String projectId);

    Optional<Stored> findWithPayload(String projectId);

    void save(HierarchySnapshotDoc doc, String payloadJson);

    void deleteById(String projectId);
}
