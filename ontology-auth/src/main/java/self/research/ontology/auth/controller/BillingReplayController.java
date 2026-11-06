package self.research.ontology.auth.controller;

import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.EmailLog;
import self.research.ontology.auth.model.StripeEvent;
import self.research.ontology.auth.model.StripeUnmatchedInvoice;
import self.research.ontology.auth.repository.EmailLogRepository;
import self.research.ontology.auth.repository.StripeEventRepository;
import self.research.ontology.auth.repository.StripeUnmatchedInvoiceRepository;
import self.research.ontology.auth.service.StripeService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/billing")
@ConditionalOnProperty(prefix = "email.test-endpoints", name = "enabled", havingValue = "true")
@CrossOrigin(originPatterns = "*")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class BillingReplayController {

    private static final Logger log = LoggerFactory.getLogger(BillingReplayController.class);

    @Autowired
    private StripeService stripeService;

    @Autowired
    private EmailLogRepository emailLogs;

    @Autowired
    private StripeEventRepository stripeEvents;

    @Autowired
    private StripeUnmatchedInvoiceRepository unmatchedInvoices;

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

            Optional<EmailLog> logRow = emailLogs.findByKey("payment-succeeded:" + invoiceId);
            if (logRow.isPresent()) {
                result.put("emailLogStatus", logRow.get().status());
                result.put("emailLogSentAt", String.valueOf(logRow.get().sentAt()));
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

        List<StripeEvent> events = stripeEvents.findByEventTypeAndStatusSince("invoice.payment_succeeded", "processed", cutoff);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sinceHours", boundedHours);
        summary.put("dryRun", dryRun);
        summary.put("candidateEventCount", events.size());

        List<Map<String, Object>> replayed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> errored = new ArrayList<>();

        for (StripeEvent event : events) {
            String eventId = event.getStripeEventId();

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

            if (emailLogs.existsWithStatus("payment-succeeded:" + invoiceId, "sent")) {
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
                Optional<EmailLog> after = emailLogs.findByKey("payment-succeeded:" + invoiceId);
                replayed.add(Map.of(
                        "eventId", eventId,
                        "invoiceId", invoiceId,
                        "emailLogStatus", after.isPresent() && after.get().status() != null ? after.get().status() : "no_log_row"));
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
        List<StripeUnmatchedInvoice> rows = unmatchedInvoices.findRecent(Math.max(1, Math.min(limit, 500)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", rows.size());
        body.put("invoices", rows);
        return ResponseEntity.ok(body);
    }
}
