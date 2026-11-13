package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class ProjectRecoveryLockMigration implements CollectionMigration {

    @Override
    public String name() {
        return "project_recovery_locks";
    }

    @Override
    public String sourceCollection() {
        return "project_recovery_locks";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("project_recovery_lock", "project_id")
                .put("project_id", Values.idText(d.get("_id")))
                .put("locked", Values.bool(d, "locked", false))
                .put("reason", Values.text(d, "reason"))
                .put("locked_at", Values.time(d, "lockedAt"))
                .put("operation_id", Values.text(d, "operationId"))
                .put("can_restore", Values.bool(d, "canRestore", false))
                .put("released_by", Values.text(d, "releasedBy"))
                .put("release_action", Values.text(d, "releaseAction"))
                .put("released_at", Values.time(d, "releasedAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
