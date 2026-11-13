package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class AssistantAuditMigration implements CollectionMigration {

    private static final java.time.Duration RETENTION = java.time.Duration.ofDays(90);

    private final java.time.Clock clock;

    public AssistantAuditMigration() {
        this(java.time.Clock.systemUTC());
    }

    public AssistantAuditMigration(java.time.Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "assistant_audit";
    }

    @Override
    public String sourceCollection() {
        return "assistant_audit";
    }

    @Override
    public Skip skipReason(Document d) {
        java.time.Instant created = Values.instant(d.get("createdAt"));
        if (created == null) {
            return new Skip("no createdAt", "the expiry date cannot be computed");
        }
        if (!created.plus(RETENTION).isAfter(clock.instant())) {
            return new Skip("past 90 day retention", "older than 90 days, it would be purged at once");
        }
        return null;
    }

    @Override
    public List<Write> map(Document d) {
        java.time.OffsetDateTime created = Values.time(d, "createdAt");
        return List.of(Write.upsert("assistant_audit", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("actor", Values.text(d, "actor"))
                .put("project_id", Values.text(d, "projectId"))
                .put("session_id", Values.text(d, "sessionId"))
                .put("group_id", Values.text(d, "groupId"))
                .put("operation", Values.text(d, "operation"))
                .put("source_revision", Values.longValue(d, "sourceRevision"))
                .put("provider", Values.text(d, "provider"))
                .put("model", Values.text(d, "model"))
                .put("outcome", Values.text(d, "outcome"))
                .put("error_code", Values.text(d, "errorCode"))
                .put("detail", Values.text(d, "detail"))
                .put("created_at", created)
                .put("expires_at", created.plus(RETENTION))
                .build());
    }
}
