package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class OntologyChangeMigration implements CollectionMigration {

    @Override
    public String name() {
        return "ontology_changes";
    }

    @Override
    public String sourceCollection() {
        return "ontology_changes";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("ontology_change", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("user_id", Values.text(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("changed_at", Values.time(d, "timestamp"))
                .put("change_type", Values.text(d, "changeType"))
                .put("change_category", Values.text(d, "changeCategory"))
                .put("entity_iri", HistoryChangeMigration.firstText(d, "entityIRI", "entityIri"))
                .put("entity_label", Values.text(d, "entityLabel"))
                .put("old_value", Values.text(d, "oldValue"))
                .put("new_value", Values.text(d, "newValue"))
                .put("axiom_before", Values.text(d, "axiomBefore"))
                .put("axiom_after", Values.text(d, "axiomAfter"))
                .put("description", Values.text(d, "description"))
                .put("comment", Values.text(d, "comment"))
                .put("metadata", Values.json(d.get("metadata")))
                .put("reverted", Values.bool(d, "reverted", false))
                .put("reverted_by", Values.text(d, "revertedBy"))
                .put("reverted_at", Values.time(d, "revertedAt"))
                .put("session_id", Values.text(d, "sessionId"))
                .put("ip_address", Values.text(d, "ipAddress"))
                .build());
    }
}
