package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.PlanFeatureConfig;

import java.util.List;
import java.util.Optional;

public interface PlanFeatureConfigRepository {

    Optional<PlanFeatureConfig> findByPlanId(String planId);

    List<PlanFeatureConfig> findAll();

    PlanFeatureConfig save(PlanFeatureConfig config);
}
