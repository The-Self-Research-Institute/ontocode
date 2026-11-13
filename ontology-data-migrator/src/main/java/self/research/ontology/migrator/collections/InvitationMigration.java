package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class InvitationMigration implements CollectionMigration {

    @Override
    public String name() {
        return "invitations";
    }

    @Override
    public String sourceCollection() {
        return "invitations";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("invitation_token", "invitationToken"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("invitation", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("invitation_token", Values.required(d, "invitationToken"))
                .put("invitee_email", Values.text(d, "inviteeEmail"))
                .put("workspace_id", Values.text(d, "workspaceId"))
                .put("workspace_name", Values.text(d, "workspaceName"))
                .put("invited_by", Values.text(d, "invitedBy"))
                .put("invited_by_email", Values.text(d, "invitedByEmail"))
                .put("role", Values.text(d, "role"))
                .put("status", Values.text(d, "status"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("accepted_at", Values.time(d, "acceptedAt"))
                .build());
    }
}
