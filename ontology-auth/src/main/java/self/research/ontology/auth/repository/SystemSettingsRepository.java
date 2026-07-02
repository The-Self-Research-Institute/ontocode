package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.SystemSettings;

@Repository
public interface SystemSettingsRepository extends MongoRepository<SystemSettings, String> {
}
