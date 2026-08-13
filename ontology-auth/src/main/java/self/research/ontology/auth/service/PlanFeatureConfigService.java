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

    @Value("${plan.pro.monthly.price:59}")
    private int defaultProMonthlyPrice;

    @Value("${plan.pro.annual.discount.percent:0}")
    private int defaultProAnnualDiscountPercent;

    @Value("${plan.enterprise.monthly.price:299}")
    private int defaultEnterpriseMonthlyPrice;

    @Value("${plan.enterprise.annual.discount.percent:0}")
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
        upsertConfig("FREE", 0, 0, defaultFreeMaxMembers,
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
                "Invite and manage members"
            ),
            List.of(
                "No team collaboration",
                "No shared editing"
            )
        );
        upsertConfig("PRO", defaultProMonthlyPrice, defaultProAnnualDiscountPercent, defaultProMaxMembers,
            List.of(
                "Up to 10 workspaces",
                "Up to 10 workspace members",
                "100 GB storage shared across workspaces",
                "Everything in Free",
                "Workspace collaboration enabled",
                "Shared editing in workspaces",
                "Export to multiple formats",
                "Priority email support"
            ),
            List.of()
        );
        upsertConfig("ENTERPRISE", defaultEnterpriseMonthlyPrice, defaultEnterpriseAnnualDiscountPercent, defaultEnterpriseMaxMembers,
            List.of(
                "Unlimited workspace members",
                "Unlimited workspaces",
                "Unlimited storage",
                "Everything in Professional",
                "Early access to new features",
                "Priority channel support"
            ),
            List.of()
        );
    }

    private void upsertConfig(String planId, int monthlyPrice, int annualDiscountPercent, int maxMembers,
                               List<String> features, List<String> limitations) {
        PlanFeatureConfig config = repo.findByPlanId(planId).orElse(new PlanFeatureConfig(planId, monthlyPrice, annualDiscountPercent, features, limitations));

        config.setMonthlyPrice(monthlyPrice);
        config.setAnnualDiscountPercent(annualDiscountPercent);
        config.setMaxMembers(maxMembers);
        config.setFeatures(features);
        config.setLimitations(limitations);

        repo.save(config);
        log.info("Upserted plan config for: {} (${}/mo, {} members max)", planId, monthlyPrice, maxMembers);
    }

    public Map<String, PlanFeatureConfig> getAllByPlanId() {
        return repo.findAll().stream()
            .collect(Collectors.toMap(PlanFeatureConfig::getPlanId, c -> c));
    }
}
