package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class UserProjectPreferenceMigration implements CollectionMigration {

    @Override
    public String name() {
        return "user_project_preferences";
    }

    @Override
    public String sourceCollection() {
        return "user_project_preferences";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("user_email+project_id", "userEmail", "projectId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("user_project_preference", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("user_email", Values.required(d, "userEmail"))
                .put("project_id", Values.required(d, "projectId"))
                .put("sync_mode", Values.text(d, "syncMode"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
