package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String id);

    List<User> findAll();

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByStripeCustomerId(String stripeCustomerId);

    Optional<User> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<User> findByVerificationToken(String verificationToken);

    Optional<User> findByPasswordResetToken(String passwordResetToken);

    User save(User user);

    void delete(User user);

    int incrementFailedLoginAttempts(String userId);

    boolean tryClaimSubscriptionCreationLock(String userId, LocalDateTime now, LocalDateTime staleBefore);

    void releaseSubscriptionCreationLock(String userId);

    boolean markPastDueIfCurrentSubscription(String userId, String subscriptionId);
}
