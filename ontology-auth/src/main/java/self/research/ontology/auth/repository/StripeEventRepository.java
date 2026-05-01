package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.auth.model.StripeEvent;

import java.util.Optional;

public interface StripeEventRepository extends MongoRepository<StripeEvent, String> {
    Optional<StripeEvent> findByStripeEventId(String stripeEventId);
    boolean existsByStripeEventId(String stripeEventId);
}
