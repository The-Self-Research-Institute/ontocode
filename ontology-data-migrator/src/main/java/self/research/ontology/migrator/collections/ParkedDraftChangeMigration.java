package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class ParkedDraftChangeMigration implements CollectionMigration {

    @Override
    public String name() {
        return "spreadsheet_import_parked_drafts";
    }

    @Override
    public String sourceCollection() {
        return "spreadsheet_import_parked_drafts";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(DraftChangeRows.map("draft_change_parked", d));
    }
}
