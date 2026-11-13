package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.SystemSettings;

import java.util.Optional;

public interface SystemSettingsRepository {

    Optional<SystemSettings> findById(String id);

    SystemSettings save(SystemSettings settings);
}
