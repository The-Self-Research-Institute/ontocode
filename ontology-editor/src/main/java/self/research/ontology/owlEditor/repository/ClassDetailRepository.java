package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.ClassDetailDocument;

import java.util.List;
import java.util.Optional;

public interface ClassDetailRepository {

    Optional<ClassDetailDocument> findByProjectIdAndClassIri(String projectId, String classIri);

    void save(ClassDetailDocument document);

    boolean saveIfAbsent(ClassDetailDocument document);

    int saveAllIfAbsent(List<ClassDetailDocument> documents);

    void deleteByProjectIdAndClassIriIn(String projectId, List<String> classIris);

    void deleteByProjectId(String projectId);
}
