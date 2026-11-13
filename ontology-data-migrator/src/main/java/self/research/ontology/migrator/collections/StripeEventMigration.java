package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class StripeEventMigration implements CollectionMigration {

    @Override
    public String name() {
        return "stripe_events";
    }

    @Override
    public String sourceCollection() {
        return "stripe_events";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("stripe_event_id", "stripeEventId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("stripe_event", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("stripe_event_id", Values.required(d, "stripeEventId"))
                .put("event_type", Values.text(d, "eventType"))
                .put("status", Values.text(d, "status"))
                .put("error_message", Values.text(d, "errorMessage"))
                .put("received_at", Values.time(d, "receivedAt"))
                .put("processed_at", Values.time(d, "processedAt"))
                .build());
    }
}
