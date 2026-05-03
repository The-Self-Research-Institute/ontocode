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

    @Value("${plan.free.max-members:3}")
    private int defaultFreeMaxMembers;

    @Value("${plan.pro.max-members:10}")
    private int defaultProMaxMembers;

    @Value("${plan.enterprise.max-members:2147483647}")
    private int defaultEnterpriseMaxMembers;

    private final PlanFeatureConfigRepository repo;

    public PlanFeatureConfigService(PlanFeatureConfigRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void seedDefaults() {
        seedIfAbsent("FREE", 0, 0, defaultFreeMaxMembers,
            List.of(
                "Up to 3 workspaces",
                "Up to 3 workspace members (owner + 2 guests)",
                "10 GB storage shared across workspaces",
                "Full ontology editing for workspace owner",
                "Class hierarchy & properties",
                "SPARQL query execution",
                "SWRL rule editor",
                "DL Query & reasoning",
                "Import OWL/TTL/RDF files",
                "Custom plugin support",
                "Community support"
            ),
            List.of(
                "Invited members are view-only",
                "No shared editing for members"
            )
        );
        seedIfAbsent("PRO", defaultProMonthlyPrice, defaultProAnnualDiscountPercent, defaultProMaxMembers,
            List.of(
                "Up to 10 workspaces",
                "Up to 10 team members",
                "100 GB storage shared across workspaces",
                "Everything in Free",
                "Full editing access for all members",
                "Invite & manage members",
                "Priority email support",
                "Export to multiple formats"
            ),
            List.of()
        );
        seedIfAbsent("ENTERPRISE", defaultEnterpriseMonthlyPrice, defaultEnterpriseAnnualDiscountPercent, defaultEnterpriseMaxMembers,
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

    private void seedIfAbsent(String planId, int monthlyPrice, int annualDiscountPercent, int maxMembers,
                               List<String> features, List<String> limitations) {
        if (repo.findByPlanId(planId).isEmpty()) {
            PlanFeatureConfig config = new PlanFeatureConfig(planId, monthlyPrice, annualDiscountPercent, features, limitations);
            config.setMaxMembers(maxMembers);
            repo.save(config);
            log.info("Seeded plan config for: {} (${}/mo, {} members max)", planId, monthlyPrice, maxMembers);
        }
    }

    public Map<String, PlanFeatureConfig> getAllByPlanId() {
        return repo.findAll().stream()
            .collect(Collectors.toMap(PlanFeatureConfig::getPlanId, c -> c));
    }
}
