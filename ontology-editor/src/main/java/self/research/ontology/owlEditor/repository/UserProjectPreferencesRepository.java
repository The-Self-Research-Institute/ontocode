package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.UserProjectPreferences;

import java.time.Instant;
import java.util.Optional;

public interface UserProjectPreferencesRepository {

    Optional<UserProjectPreferences> findByUserEmailAndProjectId(String userEmail, String projectId);

    void saveSyncMode(String userEmail, String projectId, String syncMode, Instant now);
}
