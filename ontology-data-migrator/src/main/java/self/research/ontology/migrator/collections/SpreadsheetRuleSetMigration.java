package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class SpreadsheetRuleSetMigration implements CollectionMigration {

    @Override
    public String name() {
        return "spreadsheet_rule_sets";
    }

    @Override
    public String sourceCollection() {
        return "spreadsheet_rule_sets";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("project_id+name", "projectId", "name"));
    }

    @Override
    public List<Write> map(Document d) {
        Object rules = d.get("rules");
        return List.of(Write.upsert("spreadsheet_rule_set", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("name", Values.required(d, "name"))
                .put("description", Values.text(d, "description"))
                .put("rules", Values.json(rules == null ? List.of() : rules))
                .put("created_by", Values.text(d, "createdBy"))
                .put("created_by_user_id", Values.text(d, "createdByUserId"))
                .put("updated_by", Values.text(d, "updatedBy"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
