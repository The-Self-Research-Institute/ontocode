package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class SwrlRuleMigration implements CollectionMigration {

    @Override
    public String name() {
        return "swrl_rules";
    }

    @Override
    public String sourceCollection() {
        return "swrl_rules";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("project_id+rule_name", "projectId", "ruleName"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("swrl_rule", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("rule_name", Values.required(d, "ruleName"))
                .put("rule_text", Values.text(d, "ruleText"))
                .put("comment", Values.text(d, "comment"))
                .put("category", Values.text(d, "category"))
                .put("enabled", Values.bool(d, "enabled", true))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("created_by", Values.text(d, "createdBy"))
                .put("updated_by", Values.text(d, "updatedBy"))
                .put("execution_count", Values.integer(d, "executionCount", 0))
                .put("last_execution_time", Values.longValue(d, "lastExecutionTime"))
                .put("last_executed_at", Values.time(d, "lastExecutedAt"))
                .put("validated", Values.bool(d, "validated", false))
                .put("validation_error", Values.text(d, "validationError"))
                .put("migrated_at", Values.time(d, "migratedAt"))
                .build());
    }
}
