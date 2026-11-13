package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.StripeEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StripeEventRepository {

    StripeEvent insert(StripeEvent event);

    StripeEvent save(StripeEvent event);

    Optional<StripeEvent> findByStripeEventId(String stripeEventId);

    boolean existsByStripeEventId(String stripeEventId);

    List<StripeEvent> findByEventTypeAndStatusSince(String eventType, String status, LocalDateTime receivedSince);
}
