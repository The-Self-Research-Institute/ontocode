package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class AuditEventMigration implements CollectionMigration {

    @Override
    public String name() {
        return "audit_events";
    }

    @Override
    public String sourceCollection() {
        return "audit_events";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("audit_event", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("username", Values.text(d, "username"))
                .put("event_type", Values.text(d, "eventType"))
                .put("ip_address", Values.text(d, "ipAddress"))
                .put("event_time", Values.time(d, "timestamp"))
                .put("metadata", Values.json(d.get("metadata")))
                .build());
    }
}
