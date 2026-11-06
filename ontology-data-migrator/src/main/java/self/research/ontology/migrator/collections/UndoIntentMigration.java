package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class UndoIntentMigration implements CollectionMigration {

    @Override
    public String name() {
        return "undo_intents";
    }

    @Override
    public String sourceCollection() {
        return "undo_intents";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("project_id+op_id", "projectId", "opId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("undo_intent", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("op_id", Values.text(d, "opId"))
                .put("direction", Values.text(d, "direction"))
                .put("actor", Values.text(d, "actor"))
                .put("draft", Values.bool(d, "draft", false))
                .put("change_ids", Values.list(d, "changeIds"))
                .put("audit_id", Values.text(d, "auditId"))
                .put("undo_audit_id", Values.text(d, "undoAuditId"))
                .put("scope_key", Values.text(d, "scopeKey"))
                .put("stage", Values.text(d, "stage"))
                .put("applied_count", Values.integer(d, "appliedCount", 0))
                .put("status", Values.text(d, "status"))
                .put("failure_reason", Values.text(d, "failureReason"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
