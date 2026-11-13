package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class StripeUnmatchedInvoiceMigration implements CollectionMigration {

    @Override
    public String name() {
        return "stripe_unmatched_invoices";
    }

    @Override
    public String sourceCollection() {
        return "stripe_unmatched_invoices";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("stripe_unmatched_invoice", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("invoice_id", Values.text(d, "invoiceId"))
                .put("stripe_customer_id", Values.text(d, "stripeCustomerId"))
                .put("stripe_subscription_id", Values.text(d, "stripeSubscriptionId"))
                .put("customer_email", Values.text(d, "customerEmail"))
                .put("discovered_email", Values.text(d, "discoveredEmail"))
                .put("amount_paid", Values.longValue(d, "amountPaid"))
                .put("amount_due", Values.longValue(d, "amountDue"))
                .put("currency", Values.text(d, "currency"))
                .put("hosted_invoice_url", Values.text(d, "hostedInvoiceUrl"))
                .put("recorded_at", Values.time(d, "recordedAt"))
                .put("status", Values.text(d, "status"))
                .build());
    }
}
