package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

final class EditorSkips {

    private EditorSkips() {
    }

    static Skip missingProject(Document d) {
        String p = Values.text(d, "projectId");
        return p == null || p.isBlank() ? new Skip("missing projectId", "row has no projectId") : null;
    }
}
