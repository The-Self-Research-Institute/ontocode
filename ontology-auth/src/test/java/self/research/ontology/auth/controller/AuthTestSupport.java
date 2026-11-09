package self.research.ontology.auth.controller;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Optional;

import static org.mockito.Mockito.when;

final class AuthTestSupport {

    private AuthTestSupport() {
    }

    static User stubUser(UserRepository users, String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setUsername(id);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        return user;
    }

    static void stubUserWithId(UserRepository users, String id, String email) {
        User user = stubUser(users, id, email);
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    static void signInAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }
}
