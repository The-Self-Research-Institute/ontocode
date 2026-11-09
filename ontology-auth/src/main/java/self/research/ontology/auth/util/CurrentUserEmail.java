package self.research.ontology.auth.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Optional;

public final class CurrentUserEmail {

    private CurrentUserEmail() {
    }

    public static String get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }

    public static Optional<User> findUser(UserRepository userRepository) {
        return userRepository.findByEmail(get());
    }
}
