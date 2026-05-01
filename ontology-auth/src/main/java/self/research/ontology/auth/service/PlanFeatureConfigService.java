package self.research.ontology.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.PlanFeatureConfig;
import self.research.ontology.auth.repository.PlanFeatureConfigRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanFeatureConfigService {

    private final PlanFeatureConfigRepository repo;

    public PlanFeatureConfigService(PlanFeatureConfigRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void seedDefaults() {
        seedIfAbsent("FREE",
            List.of(
                "Up to 3 workspaces",
                "Up to 3 workspace members",
                "10 GB storage",
                "OWL/RDF ontology editing",
                "Class hierarchy & properties",
                "SPARQL query execution",
                "SWRL rule editor",
                "DL Query & reasoning",
                "Import OWL/TTL/RDF files",
                "Custom plugin support",
                "Community support"
            ),
            List.of(
                "No team collaboration",
                "No shared editing"
            )
        );
        seedIfAbsent("PRO",
            List.of(
                "Up to 10 workspaces",
                "Up to 10 team members",
                "100 GB storage",
                "Everything in Free",
                "Team collaboration enabled",
                "Invite & manage members",
                "Priority email support",
                "Export to multiple formats"
            ),
            List.of()
        );
        seedIfAbsent("ENTERPRISE",
            List.of(
                "Unlimited team members",
                "Unlimited workspaces",
                "Unlimited storage",
                "Everything in Professional",
                "Priority support channel",
                "Early access to new features"
            ),
            List.of()
        );
    }

    private void seedIfAbsent(String planId, List<String> features, List<String> limitations) {
        if (repo.findByPlanId(planId).isEmpty()) {
            repo.save(new PlanFeatureConfig(planId, features, limitations));
            log.info("Seeded plan feature config for plan: {}", planId);
        }
    }

    public Map<String, PlanFeatureConfig> getAllByPlanId() {
        return repo.findAll().stream()
            .collect(Collectors.toMap(PlanFeatureConfig::getPlanId, c -> c));
    }
}
