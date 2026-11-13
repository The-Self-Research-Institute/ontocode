package self.research.ontology.auth.service;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import self.research.ontology.auth.model.User;

@Component
public class NewSubscriptionCreator {

    private static final Logger log = LoggerFactory.getLogger(NewSubscriptionCreator.class);
    private static final String EXPAND_PAYMENT_INTENT = "latest_invoice.payment_intent";

    private final SubscriptionStripeApi stripe;

    public NewSubscriptionCreator(SubscriptionStripeApi stripe) {
        this.stripe = stripe;
    }

    public Subscription create(User user, SubscriptionCreateParams.Builder params, String setupIntentId)
            throws StripeException {
        Subscription stale = liveIncomplete(user);
        if (stale != null) {
            if (setupIntentId != null && setupIntentId.equals(stale.getMetadata().get("setupIntentId"))) {
                return failIfDeclined(stale);
            }
            cancelStale(stale, user);
        }
        Subscription subscription = stripe.create(
                params.putMetadata("setupIntentId", setupIntentId).addExpand(EXPAND_PAYMENT_INTENT).build(),
                "sub-create-" + user.getId() + "-" + setupIntentId);
        return failIfDeclined(subscription);
    }

    private Subscription failIfDeclined(Subscription subscription) {
        if (isIncomplete(subscription) && "requires_payment_method".equals(paymentIntentStatus(subscription))) {
            cancelQuietly(subscription);
            throw new CardDeclinedException();
        }
        return subscription;
    }

    public SubscriptionResult resultFor(Subscription subscription) {
        boolean requiresAction = isIncomplete(subscription)
                && "requires_action".equals(paymentIntentStatus(subscription));
        String secret = requiresAction ? paymentIntent(subscription).getClientSecret() : null;
        return new SubscriptionResult(subscription.getId(), subscription.getStatus(), requiresAction, secret);
    }

    private Subscription liveIncomplete(User user) throws StripeException {
        String oldId = user.getStripeSubscriptionId();
        if (oldId == null || oldId.isBlank()) {
            return null;
        }
        Subscription old;
        try {
            old = stripe.retrieve(oldId);
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return null;
            }
            throw e;
        }
        if (!isIncomplete(old) || user.getStripeCustomerId() == null
                || !user.getStripeCustomerId().equals(old.getCustomer())) {
            return null;
        }
        if (old.getMetadata() == null) {
            old.setMetadata(Map.of());
        }
        return old;
    }

    private void cancelStale(Subscription old, User user) {
        try {
            stripe.cancel(old);
            log.info("Cancelled stale incomplete subscription {} for user {}", old.getId(), user.getId());
        } catch (StripeException e) {
            log.error("Could not cancel stale incomplete subscription {} for user {}: {}",
                    old.getId(), user.getId(), e.getMessage());
            throw new IllegalStateException(
                    "We could not clear your previous unfinished payment. Please try again in a moment.");
        }
    }

    private void cancelQuietly(Subscription subscription) {
        try {
            stripe.cancel(subscription);
        } catch (StripeException e) {
            log.warn("Could not cancel declined subscription {}: {}", subscription.getId(), e.getMessage());
        }
    }

    private static boolean isIncomplete(Subscription subscription) {
        return subscription != null && "incomplete".equals(subscription.getStatus());
    }

    private static PaymentIntent paymentIntent(Subscription subscription) {
        Invoice invoice = subscription.getLatestInvoiceObject();
        return invoice == null ? null : invoice.getPaymentIntentObject();
    }

    private static String paymentIntentStatus(Subscription subscription) {
        PaymentIntent intent = paymentIntent(subscription);
        return intent == null ? null : intent.getStatus();
    }
}
