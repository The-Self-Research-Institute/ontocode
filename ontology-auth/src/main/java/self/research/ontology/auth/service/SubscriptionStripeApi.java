package self.research.ontology.auth.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionCreateParams;

public interface SubscriptionStripeApi {

    Subscription retrieve(String subscriptionId) throws StripeException;

    Subscription cancel(Subscription subscription) throws StripeException;

    Subscription create(SubscriptionCreateParams params, String idempotencyKey) throws StripeException;
}
