package self.research.ontology.auth.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionRetrieveParams;
import org.springframework.stereotype.Component;

@Component
public class StripeSubscriptionApi implements SubscriptionStripeApi {

    @Override
    public Subscription retrieve(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId, SubscriptionRetrieveParams.builder()
                .addExpand("latest_invoice.payment_intent").build(), null);
    }

    @Override
    public Subscription cancel(Subscription subscription) throws StripeException {
        return subscription.cancel();
    }

    @Override
    public Subscription create(SubscriptionCreateParams params, String idempotencyKey) throws StripeException {
        return Subscription.create(params,
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
    }
}
