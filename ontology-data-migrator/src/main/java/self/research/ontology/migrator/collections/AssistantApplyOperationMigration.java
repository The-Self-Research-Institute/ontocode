package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class AssistantApplyOperationMigration implements CollectionMigration {

    @Override
    public String name() {
        return "assistant_apply_operations";
    }

    @Override
    public String sourceCollection() {
        return "assistant_apply_operations";
    }

    @Override
    public Skip skipReason(Document d) {
        String status = Values.text(d, "status");
        boolean open = "PREPARED".equals(status) || "GRAPH_IMPORTING".equals(status)
                || ("FAILED".equals(status) && d.get("resolvedAt") == null);
        return open ? null : new Skip("finished operation", "status " + status + " is already resolved");
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("assistant_apply_operation", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.text(d, "projectId"))
                .put("group_id", Values.text(d, "groupId"))
                .put("session_id", Values.text(d, "sessionId"))
                .put("actor", Values.text(d, "actor"))
                .put("target_path", Values.text(d, "targetPath"))
                .put("status", Values.text(d, "status"))
                .put("snapshot_path", Values.text(d, "snapshotPath"))
                .put("failure_reason", Values.text(d, "failureReason"))
                .put("resolution", Values.text(d, "resolution"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("resolved_at", Values.time(d, "resolvedAt"))
                .build());
    }
}
