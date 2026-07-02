package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.owlEditor.document.ClassDetailDocument;

import java.util.List;
import java.util.Optional;

public interface ClassDetailRepository extends MongoRepository<ClassDetailDocument, String> {

    Optional<ClassDetailDocument> findByProjectIdAndClassIri(String projectId, String classIri);

    void deleteByProjectIdAndClassIriIn(String projectId, List<String> classIris);

    void deleteByProjectId(String projectId);
}
