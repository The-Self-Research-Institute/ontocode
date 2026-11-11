package self.research.ontology.auth.service;

import com.stripe.model.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Optional;

final class PastDueMarker {

    private static final Logger log = LoggerFactory.getLogger(PastDueMarker.class);

    private PastDueMarker() {
    }

    static Optional<User> apply(UserRepository users, User user, Invoice invoice) {
        String subscriptionId = invoice.getSubscription();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            user.setSubscriptionStatus("past_due");
            return Optional.of(users.save(user));
        }
        if (!users.markPastDueIfCurrentSubscription(user.getId(), subscriptionId)) {
            log.info("[InvoicePaymentFailed] Ignoring failed invoice {} for user {}: subscription {} is no longer their current one or is cancelled",
                    invoice.getId(), user.getUsername(), subscriptionId);
            return Optional.empty();
        }
        return users.findById(user.getId());
    }
}
