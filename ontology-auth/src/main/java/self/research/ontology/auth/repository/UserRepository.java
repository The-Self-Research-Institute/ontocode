package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.User;

import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    
    /**
     * Find user by username
     */
    Optional<User> findByUsername(String username);
    
    /**
     * Find user by email
     */
    Optional<User> findByEmail(String email);

    /**
     * Find user by email without requiring exact casing
     */
    Optional<User> findByEmailIgnoreCase(String email);
    
    /**
     * Find user by email verification token
     */
    Optional<User> findByVerificationToken(String verificationToken);
    
    /**
     * Find user by password reset token
     */
    Optional<User> findByPasswordResetToken(String passwordResetToken);
}