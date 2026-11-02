package self.research.ontology.auth.service;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Optional;

@Component
public class AuthenticatedUsers {

    private final UserRepository userRepository;

    public AuthenticatedUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> find(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName())
                .or(() -> userRepository.findByUsername(authentication.getName()));
    }
}
