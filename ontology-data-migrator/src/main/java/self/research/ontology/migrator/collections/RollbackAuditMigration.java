package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class RollbackAuditMigration implements CollectionMigration {

    @Override
    public String name() {
        return "rollback_audit";
    }

    @Override
    public String sourceCollection() {
        return "rollback_audit";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        Object records = d.get("draftRecords");
        boolean hasRecords = records instanceof Collection<?> c && !c.isEmpty();
        String direction = Values.text(d, "direction");
        String format = hasRecords ? "mongo" : (Values.text(d, "format") == null ? "json" : Values.text(d, "format"));
        return List.of(Write.upsert("rollback_audit", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("history_change_id", Values.text(d, "historyChangeId"))
                .put("sub_change_id", Values.text(d, "subChangeId"))
                .put("entity_iri", HistoryChangeMigration.firstText(d, "entityIRI", "entityIri"))
                .put("entity_label", Values.text(d, "entityLabel"))
                .put("predicate", Values.text(d, "predicate"))
                .put("action", Values.text(d, "action"))
                .put("reverted_by_user_id", Values.text(d, "revertedByUserId"))
                .put("reverted_by_username", Values.text(d, "revertedByUsername"))
                .put("reverted_at", Values.time(d, "revertedAt"))
                .put("cascaded", Values.bool(d, "cascaded", false))
                .put("cascaded_sub_change_ids", Values.list(d, "cascadedSubChangeIds"))
                .put("change_set_id", Values.text(d, "changeSetId"))
                .put("direction", direction == null ? "UNDO" : direction)
                .put("change_ids", Values.list(d, "changeIds"))
                .put("skipped_count", Values.integer(d, "skippedCount", 0))
                .put("draft_records", hasRecords ? Values.json(records) : null)
                .put("format", format)
                .build());
    }
}
