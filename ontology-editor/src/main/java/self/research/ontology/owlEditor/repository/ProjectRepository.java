package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.ProjectDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends MongoRepository<ProjectDocument, String> {
    List<ProjectDocument> findAllByOrderByUpdatedAtDesc();
    Optional<ProjectDocument> findByFilenameAndOwnerEmail(String filename, String ownerEmail);
}
