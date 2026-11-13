package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class PlanFeatureConfigMigration implements CollectionMigration {

    @Override
    public String name() {
        return "plan_feature_configs";
    }

    @Override
    public String sourceCollection() {
        return "plan_feature_configs";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("plan_id", "planId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("plan_feature_config", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("plan_id", Values.required(d, "planId"))
                .put("monthly_price", Values.integer(d, "monthlyPrice", 0))
                .put("annual_discount_percent", Values.integer(d, "annualDiscountPercent", 0))
                .put("features", Values.list(d, "features"))
                .put("limitations", Values.list(d, "limitations"))
                .put("max_members", Values.integer(d, "maxMembers", 0))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
