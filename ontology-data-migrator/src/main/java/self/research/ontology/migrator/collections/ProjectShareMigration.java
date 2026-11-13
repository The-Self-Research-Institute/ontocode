package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class ProjectShareMigration implements CollectionMigration {

    @Override
    public String name() {
        return "project_shares";
    }

    @Override
    public String sourceCollection() {
        return "project_shares";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("project_id", "projectId"), UniqueRule.field("share_link", "shareLink"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("project_share", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("owner_email", Values.text(d, "ownerEmail"))
                .put("shared_with_emails", Values.listOrEmpty(d, "sharedWithEmails"))
                .put("share_link", Values.text(d, "shareLink"))
                .put("permission", Values.text(d, "permission"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
