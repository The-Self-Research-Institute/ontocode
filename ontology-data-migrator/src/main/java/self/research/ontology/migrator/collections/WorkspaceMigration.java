package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.ArrayList;
import java.util.List;

public final class WorkspaceMigration implements CollectionMigration {

    @Override
    public String name() {
        return "workspaces";
    }

    @Override
    public String sourceCollection() {
        return "workspaces";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("workspace_id", "workspaceId"));
    }

    @Override
    public List<Write> map(Document d) {
        String id = Values.idText(d.get("_id"));
        List<Write> writes = new ArrayList<>();
        writes.add(Write.upsert("workspace", "id")
                .put("id", id)
                .put("workspace_id", Values.required(d, "workspaceId"))
                .put("owner_id", Values.required(d, "ownerId"))
                .put("name", Values.text(d, "name"))
                .put("description", Values.text(d, "description"))
                .put("subscription_plan", Values.text(d, "subscriptionPlan"))
                .put("billing_status", orDefault(Values.text(d, "billingStatus"), "ACTIVE"))
                .put("billing_interval", orDefault(Values.text(d, "billingInterval"), "monthly"))
                .put("subscription_start_date", Values.time(d, "subscriptionStartDate"))
                .put("subscription_end_date", Values.time(d, "subscriptionEndDate"))
                .put("subscription_current_period_end", Values.time(d, "subscriptionCurrentPeriodEnd"))
                .put("max_workspaces", Values.integer(d, "maxWorkspaces"))
                .put("max_members", Values.integer(d, "maxMembers"))
                .put("collaboration_enabled", Values.bool(d, "collaborationEnabled", false))
                .put("stripe_subscription_id", Values.text(d, "stripeSubscriptionId"))
                .put("pending_checkout_session_id", Values.text(d, "pendingCheckoutSessionId"))
                .put("pending_checkout_created_at", Values.time(d, "pendingCheckoutCreatedAt"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("is_deleted", Values.bool(d, "isDeleted", false))
                .put("deleted_at", Values.time(d, "deletedAt"))
                .put("deleted_by", Values.text(d, "deletedBy"))
                .build());
        writes.add(Write.deleteChildren("workspace_member", "workspace_row_id", id));
        int position = 0;
        for (Document m : Values.documents(d, "members")) {
            writes.add(Write.upsert("workspace_member", "workspace_row_id", "position")
                    .put("workspace_row_id", id)
                    .put("position", position++)
                    .put("user_id", Values.text(m, "userId"))
                    .put("username", Values.text(m, "username"))
                    .put("email", Values.text(m, "email"))
                    .put("role", Values.text(m, "role"))
                    .put("status", Values.text(m, "status"))
                    .put("invitation_token", Values.text(m, "invitationToken"))
                    .put("joined_at", Values.time(m, "joinedAt"))
                    .build());
        }
        return writes;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
