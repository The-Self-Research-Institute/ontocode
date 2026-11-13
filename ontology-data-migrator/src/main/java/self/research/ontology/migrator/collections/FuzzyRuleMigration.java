package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class FuzzyRuleMigration implements CollectionMigration {

    @Override
    public String name() {
        return "fuzzy_rules";
    }

    @Override
    public String sourceCollection() {
        return "fuzzy_rules";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("fuzzy_rule", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("name", Values.text(d, "name"))
                .put("rule_condition", Values.text(d, "condition"))
                .put("rule_action", Values.text(d, "action"))
                .put("enabled", Values.bool(d, "enabled", true))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
