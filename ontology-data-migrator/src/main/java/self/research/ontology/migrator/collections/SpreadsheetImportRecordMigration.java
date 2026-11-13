package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class SpreadsheetImportRecordMigration implements CollectionMigration {

    @Override
    public String name() {
        return "spreadsheet_imports";
    }

    @Override
    public String sourceCollection() {
        return "spreadsheet_imports";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("spreadsheet_import_record", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("user_id", Values.text(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("file_name", Values.text(d, "fileName"))
                .put("draft", Values.bool(d, "draft", false))
                .put("draft_session_id", Values.text(d, "draftSessionId"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("axiom_count", Values.integer(d, "axiomCount", 0))
                .put("already_present_count", Values.integer(d, "alreadyPresentCount", 0))
                .put("added_triples", Values.listOrEmpty(d, "addedTriples"))
                .put("rolled_back", Values.bool(d, "rolledBack", false))
                .put("rolled_back_at", Values.time(d, "rolledBackAt"))
                .put("rolled_back_by", Values.text(d, "rolledBackBy"))
                .build());
    }
}
