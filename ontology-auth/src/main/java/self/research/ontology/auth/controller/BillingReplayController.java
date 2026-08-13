package self.research.ontology.auth.controller;

import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.service.StripeService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/billing")
@ConditionalOnProperty(prefix = "email.test-endpoints", name = "enabled", havingValue = "true")
@CrossOrigin(originPatterns = "*")
public class BillingReplayController {

    private static final Logger log = LoggerFactory.getLogger(BillingReplayController.class);

    @Autowired
    private StripeService stripeService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @PostMapping("/replay-invoice/{invoiceId}")
    public ResponseEntity<Map<String, Object>> replayInvoice(@PathVariable String invoiceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceId", invoiceId);

        Invoice invoice;
        try {
            invoice = Invoice.retrieve(invoiceId);
        } catch (StripeException e) {
            log.error("[BillingReplay] Stripe Invoice.retrieve failed for {}: {}", invoiceId, e.getMessage());
            result.put("status", "stripe_lookup_failed");
            result.put("error", e.getMessage());
            return ResponseEntity.status(404).body(result);
        }

        try {
            stripeService.handleInvoicePaymentSucceeded(invoice);
            result.put("status", "replayed");

            Document logDoc = mongoTemplate.findOne(
                    new Query(Criteria.where("key").is("payment-succeeded:" + invoiceId)),
                    Document.class, "email_logs");
            if (logDoc != null) {
                result.put("emailLogStatus", logDoc.getString("status"));
                result.put("emailLogSentAt", String.valueOf(logDoc.get("sentAt")));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[BillingReplay] handleInvoicePaymentSucceeded threw for {}: {}", invoiceId, e.getMessage(), e);
            result.put("status", "handler_threw");
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/backfill-payment-emails")
    public ResponseEntity<Map<String, Object>> backfillPaymentEmails(
            @RequestParam(value = "sinceHours", defaultValue = "168") int sinceHours,
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {

        int boundedHours = Math.max(1, Math.min(sinceHours, 720));
        LocalDateTime cutoff = LocalDateTime.now().minusHours(boundedHours);

        Query candidates = new Query(Criteria.where("eventType").is("invoice.payment_succeeded")
                .and("status").is("processed")
                .and("receivedAt").gte(cutoff));
        List<Document> events = mongoTemplate.find(candidates, Document.class, "stripe_events");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sinceHours", boundedHours);
        summary.put("dryRun", dryRun);
        summary.put("candidateEventCount", events.size());

        List<Map<String, Object>> replayed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> errored = new ArrayList<>();

        for (Document event : events) {
            String eventId = event.getString("stripeEventId");

            String invoiceId;
            try {
                com.stripe.model.Event stripeEvent = com.stripe.model.Event.retrieve(eventId);
                Object obj = stripeEvent.getDataObjectDeserializer().getObject().orElse(null);
                if (obj == null) {
                    obj = stripeEvent.getDataObjectDeserializer().deserializeUnsafe();
                }
                if (!(obj instanceof Invoice inv)) {
                    skipped.add(Map.of("eventId", eventId, "reason", "not_an_invoice"));
                    continue;
                }
                invoiceId = inv.getId();
            } catch (Exception ex) {
                errored.add(Map.of("eventId", eventId, "reason", "stripe_retrieve_failed", "error", ex.getMessage()));
                continue;
            }

            Document existing = mongoTemplate.findOne(
                    new Query(Criteria.where("key").is("payment-succeeded:" + invoiceId)
                            .and("status").is("sent")),
                    Document.class, "email_logs");
            if (existing != null) {
                skipped.add(Map.of("eventId", eventId, "invoiceId", invoiceId, "reason", "email_already_sent"));
                continue;
            }

            if (dryRun) {
                replayed.add(Map.of("eventId", eventId, "invoiceId", invoiceId, "wouldReplay", true));
                continue;
            }

            try {
                Invoice invoice = Invoice.retrieve(invoiceId);
                stripeService.handleInvoicePaymentSucceeded(invoice);
                Document after = mongoTemplate.findOne(
                        new Query(Criteria.where("key").is("payment-succeeded:" + invoiceId)),
                        Document.class, "email_logs");
                replayed.add(Map.of(
                        "eventId", eventId,
                        "invoiceId", invoiceId,
                        "emailLogStatus", after != null ? after.getString("status") : "no_log_row"));
            } catch (Exception ex) {
                errored.add(Map.of("eventId", eventId, "invoiceId", invoiceId, "reason", "handler_threw", "error", ex.getMessage()));
            }
        }

        summary.put("replayed", replayed);
        summary.put("skipped", skipped);
        summary.put("errored", errored);
        log.info("[BillingReplay] backfill summary: replayed={} skipped={} errored={} dryRun={}",
                replayed.size(), skipped.size(), errored.size(), dryRun);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/unmatched-invoices")
    public ResponseEntity<Map<String, Object>> listUnmatchedInvoices(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<Document> rows = mongoTemplate.find(
                new Query().limit(Math.max(1, Math.min(limit, 500))),
                Document.class,
                "stripe_unmatched_invoices");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", rows.size());
        body.put("invoices", rows);
        return ResponseEntity.ok(body);
    }
}
