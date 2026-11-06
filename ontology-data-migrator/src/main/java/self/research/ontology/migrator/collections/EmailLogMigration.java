package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class EmailLogMigration implements CollectionMigration {

    @Override
    public String name() {
        return "email_logs";
    }

    @Override
    public String sourceCollection() {
        return "email_logs";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("log_key", "key"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("email_log", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("log_key", Values.required(d, "key"))
                .put("type", Values.text(d, "type"))
                .put("status", Values.text(d, "status"))
                .put("kind", Values.text(d, "kind"))
                .put("days_before", Values.integer(d, "daysBefore"))
                .put("period_end", Values.time(d, "periodEnd"))
                .put("email", Values.text(d, "email"))
                .put("user_id", Values.text(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("stripe_invoice_id", Values.text(d, "stripeInvoiceId"))
                .put("stripe_customer_id", Values.text(d, "stripeCustomerId"))
                .put("stripe_subscription_id", Values.text(d, "stripeSubscriptionId"))
                .put("amount_paid", Values.longValue(d, "amountPaid"))
                .put("currency", Values.text(d, "currency"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("sent_at", Values.time(d, "sentAt"))
                .put("failed_at", Values.time(d, "failedAt"))
                .put("error_message", Values.text(d, "errorMessage"))
                .build());
    }
}
