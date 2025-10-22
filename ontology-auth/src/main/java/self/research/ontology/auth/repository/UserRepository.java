package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.auth.model.User;

import java.util.Optional;

public interface UserRepository extends MongoRepository<self.research.ontology.auth.model.User, String> {
    Optional<self.research.ontology.auth.model.User> findByUsername(String username);
    Optional<self.research.ontology.auth.model.User> findByVerificationToken(String verificationToken);
}