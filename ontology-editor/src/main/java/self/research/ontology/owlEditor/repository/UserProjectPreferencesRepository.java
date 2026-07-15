package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.UserProjectPreferences;

import java.util.Optional;

@Repository
public interface UserProjectPreferencesRepository extends MongoRepository<UserProjectPreferences, String> {

    Optional<UserProjectPreferences> findByUserEmailAndProjectId(String userEmail, String projectId);
}
