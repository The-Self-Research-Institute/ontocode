package self.research.ontology.auth.controller;

import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.StripeEvent;
import self.research.ontology.auth.repository.StripeEventRepository;
import self.research.ontology.auth.service.StripeService;

import java.time.LocalDateTime;

/**
 * Receives and processes Stripe webhook events.
 *
 * Security design:
 * 1. This endpoint is explicitly excluded from JWT authentication (see SecurityConfig).
 * 2. EVERY request is validated against the Stripe-Signature header before processing.
 *    Requests with invalid or missing signatures are rejected with HTTP 400.
 * 3. The raw request body bytes are passed directly to the signature verifier —
 *    no deserialization occurs before verification.
 * 4. Idempotency: each event ID is stored in MongoDB. Duplicate events are silently ignored.
 * 5. Response timing: we always respond quickly (200/400) regardless of processing outcome
 *    to prevent Stripe from retrying valid events due to slow handlers.
 *
 * Billing sync model (high level):
 * - Subscription state is driven primarily by Stripe webhooks (subscription + invoice events).
 * - Card payments typically settle immediately; US bank debits (ACH) can take several days.
 *   While pending, Stripe may emit {@code invoice.payment_action_required} or leave the invoice open;
 *   when funds settle you receive {@code invoice.paid} / {@code invoice.payment_succeeded}.
 * - If a delayed debit ultimately fails, {@code invoice.payment_failed} runs the same path as cards:
 *   we mark the account {@code past_due}, sync workspaces, and email the billing contact.
 * - For full reconciliation beyond this (disputes, partial refunds, Connect), extend handlers and
 *   consider periodic reconciliation jobs against the Stripe API.
 */
@RestController
@RequestMapping("/api/billing")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeService stripeService;
    private final StripeEventRepository stripeEventRepository;

    public StripeWebhookController(StripeService stripeService, StripeEventRepository stripeEventRepository) {
        this.stripeService = stripeService;
        this.stripeEventRepository = stripeEventRepository;
    }

    /**
     * Stripe webhook receiver.
     *
     * @param payload   raw HTTP body (must be the original bytes, not parsed)
     * @param sigHeader Stripe-Signature header value
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody byte[] payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        // ── Step 1: Verify Stripe signature ──────────────────────────────────
        Event event;
        try {
            event = stripeService.constructWebhookEvent(payload, sigHeader);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        // ── Step 2: Atomic idempotency lock via unique index ────────────────
        StripeEvent record = new StripeEvent(event.getId(), event.getType());
        record.setStatus("processing");
        try {
            stripeEventRepository.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("Duplicate Stripe event {} — skipping", event.getId());
            return ResponseEntity.ok("Already processed");
        }

        // ── Step 3: Dispatch to handler ───────────────────────────────────────
        try {
            dispatch(event);
            record.setStatus("processed");
            record.setProcessedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("Error processing Stripe event {} ({}): {}", event.getId(), event.getType(), e.getMessage(), e);
            record.setStatus("failed");
            record.setErrorMessage(e.getMessage());
            record.setProcessedAt(LocalDateTime.now());
            // Persist failure record, then return 200 so Stripe doesn't retry indefinitely.
            // Failures should be investigated via the stripe_events collection.
        }

        try {
            stripeEventRepository.save(record);
        } catch (Exception e) {
            // Do not force Stripe retries for persistence issues after processing.
            log.error("Failed to persist final status for Stripe event {}: {}", event.getId(), e.getMessage(), e);
        }
        return ResponseEntity.ok("Received");
    }

    private void dispatch(Event event) {
        StripeObject stripeObject = deserializeStripeObject(event);

        switch (event.getType()) {
            case "checkout.session.completed" -> {
                Session session = (Session) stripeObject;
                stripeService.handleCheckoutSessionCompleted(session);
            }
            case "customer.subscription.created" -> {
                Subscription subscription = (Subscription) stripeObject;
                stripeService.handleSubscriptionCreated(subscription);
            }
            case "customer.subscription.updated" -> {
                Subscription subscription = (Subscription) stripeObject;
                stripeService.handleSubscriptionUpdated(subscription);
            }
            case "customer.subscription.trial_will_end" -> {
                Subscription subscription = (Subscription) stripeObject;
                stripeService.handleTrialWillEnd(subscription);
            }
            case "customer.subscription.deleted" -> {
                Subscription subscription = (Subscription) stripeObject;
                stripeService.handleSubscriptionDeleted(subscription);
            }
            case "invoice.payment_succeeded" -> {
                Invoice invoice = (Invoice) stripeObject;
                stripeService.handleInvoicePaymentSucceeded(invoice);
            }
            case "invoice.paid" -> {
                Invoice invoice = (Invoice) stripeObject;
                stripeService.handleInvoicePaymentSucceeded(invoice);
            }
            case "invoice.payment_failed" -> {
                Invoice invoice = (Invoice) stripeObject;
                stripeService.handleInvoicePaymentFailed(invoice);
            }
            case "invoice.payment_action_required" -> {
                Invoice invoice = (Invoice) stripeObject;
                stripeService.handleInvoicePaymentActionRequired(invoice);
            }
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
        }
    }

    private StripeObject deserializeStripeObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            return deserializer.getObject().get();
        }

        log.warn(
                "Stripe webhook {} ({}) API version {} does not match stripe-java {}; deserializing from raw JSON",
                event.getId(),
                event.getType(),
                event.getApiVersion(),
                Stripe.API_VERSION
        );

        try {
            return deserializer.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new IllegalStateException(
                    "Could not deserialize Stripe event object for type: " + event.getType(),
                    e
            );
        }
    }
}
