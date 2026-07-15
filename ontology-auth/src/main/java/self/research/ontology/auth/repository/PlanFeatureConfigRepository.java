package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.auth.model.PlanFeatureConfig;

import java.util.Optional;

public interface PlanFeatureConfigRepository extends MongoRepository<PlanFeatureConfig, String> {
    Optional<PlanFeatureConfig> findByPlanId(String planId);
}
