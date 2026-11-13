package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class DraftChangeMigration implements CollectionMigration {

    @Override
    public String name() {
        return "draft_changes";
    }

    @Override
    public String sourceCollection() {
        return "draft_changes";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(DraftChangeRows.map("draft_change", d));
    }
}
