package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

final class DraftChangeRows {

    private DraftChangeRows() {
    }

    static Write map(String table, Document d) {
        return Write.upsert(table, "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("user_id", Values.text(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("operation_type", Values.text(d, "operationType"))
                .put("operation_data", Values.json(d.get("operationData")))
                .put("changed_at", Values.time(d, "timestamp"))
                .put("session_id", Values.text(d, "sessionId"))
                .put("applied", Values.bool(d, "applied", false))
                .build();
    }
}
