package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class HistoryChangeMigration implements CollectionMigration {

    @Override
    public String name() {
        return "history_changes";
    }

    @Override
    public String sourceCollection() {
        return "history_changes";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    static String firstText(Document d, String... keys) {
        for (String k : keys) {
            String v = Values.text(d, k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static List<Object> subChanges(Document d) {
        List<Object> out = new ArrayList<>();
        for (Document sub : Values.documents(d, "subChanges")) {
            Map<String, Object> copy = new LinkedHashMap<>(sub);
            Object oid = copy.remove("_id");
            if (oid != null && copy.get("id") == null) {
                copy.put("id", Values.idText(oid));
            }
            out.add(copy);
        }
        return out;
    }

    @Override
    public List<Write> map(Document d) {
        String before = Values.text(d, "documentBefore");
        String after = Values.text(d, "documentAfter");
        boolean snapshot = (before != null && !before.isEmpty()) || (after != null && !after.isEmpty());
        String format = snapshot ? "mongo" : (Values.text(d, "format") == null ? "json" : Values.text(d, "format"));
        String status = Values.text(d, "status");
        return List.of(Write.upsert("history_change", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("edit_id", Values.text(d, "editId"))
                .put("user_id", Values.text(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("changed_at", Values.time(d, "timestamp"))
                .put("operation_type", Values.text(d, "operationType"))
                .put("entity_type", Values.text(d, "entityType"))
                .put("entity_iri", firstText(d, "entityIRI", "entityIri"))
                .put("entity_label", Values.text(d, "entityLabel"))
                .put("old_value", Values.text(d, "oldValue"))
                .put("new_value", Values.text(d, "newValue"))
                .put("annotation_property", Values.text(d, "annotationProperty"))
                .put("description", Values.text(d, "description"))
                .put("status", status == null ? "SAVED" : status)
                .put("approved_by", Values.text(d, "approvedBy"))
                .put("approved_at", Values.time(d, "approvedAt"))
                .put("rejected_by", Values.text(d, "rejectedBy"))
                .put("rejected_at", Values.time(d, "rejectedAt"))
                .put("comments", Values.json(d.get("comments")))
                .put("has_conflict", Values.bool(d, "hasConflict", false))
                .put("conflict_resolution", Values.text(d, "conflictResolution"))
                .put("resolved_by", Values.text(d, "resolvedBy"))
                .put("resolved_at", Values.time(d, "resolvedAt"))
                .put("metadata", Values.json(d.get("metadata")))
                .put("sub_changes", Values.json(subChanges(d)))
                .put("draft", Values.bool(d, "draft", false))
                .put("draft_discarded", Values.bool(d, "draftDiscarded", false))
                .put("draft_session_id", Values.text(d, "draftSessionId"))
                .put("published_pr_id", Values.text(d, "publishedPrId"))
                .put("reverted", Values.bool(d, "reverted", false))
                .put("reverted_audit_id", Values.text(d, "revertedAuditId"))
                .put("change_set_id", Values.text(d, "changeSetId"))
                .put("exact_deleted", Values.text(d, "exactDeleted"))
                .put("exact_inserted", Values.text(d, "exactInserted"))
                .put("covered_by", Values.text(d, "coveredBy"))
                .put("exact_covers", Values.integer(d, "exactCovers", 0))
                .put("document_store", Values.text(d, "documentStore"))
                .put("undo_blocked_reason", Values.text(d, "undoBlockedReason"))
                .put("operation_group_id", Values.text(d, "operationGroupId"))
                .put("document_id", Values.text(d, "documentId"))
                .put("document_before", before)
                .put("document_after", after)
                .put("source", Values.text(d, "source"))
                .put("ai", Values.json(d.get("ai")))
                .put("synced_at", Values.time(d, "syncedAt"))
                .put("format", format)
                .build());
    }
}
