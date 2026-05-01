package self.research.ontology.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.PlanFeatureConfig;
import self.research.ontology.auth.repository.PlanFeatureConfigRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanFeatureConfigService {

    // Seed defaults — only used on first startup if the collection is empty.
    // After that, all values are read from MongoDB and can be changed there.
    @Value("${plan.pro.monthly.price:29}")
    private int defaultProMonthlyPrice;

    @Value("${plan.pro.annual.discount.percent:17}")
    private int defaultProAnnualDiscountPercent;

    @Value("${plan.enterprise.monthly.price:99}")
    private int defaultEnterpriseMonthlyPrice;

    @Value("${plan.enterprise.annual.discount.percent:20}")
    private int defaultEnterpriseAnnualDiscountPercent;

    private final PlanFeatureConfigRepository repo;

    public PlanFeatureConfigService(PlanFeatureConfigRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void seedDefaults() {
        seedIfAbsent("FREE", 0, 0,
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
        seedIfAbsent("PRO", defaultProMonthlyPrice, defaultProAnnualDiscountPercent,
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
        seedIfAbsent("ENTERPRISE", defaultEnterpriseMonthlyPrice, defaultEnterpriseAnnualDiscountPercent,
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

    private void seedIfAbsent(String planId, int monthlyPrice, int annualDiscountPercent,
                               List<String> features, List<String> limitations) {
        if (repo.findByPlanId(planId).isEmpty()) {
            repo.save(new PlanFeatureConfig(planId, monthlyPrice, annualDiscountPercent, features, limitations));
            log.info("Seeded plan config for: {} (${}/mo, {}% annual discount)", planId, monthlyPrice, annualDiscountPercent);
        }
    }

    public Map<String, PlanFeatureConfig> getAllByPlanId() {
        return repo.findAll().stream()
            .collect(Collectors.toMap(PlanFeatureConfig::getPlanId, c -> c));
    }
}
