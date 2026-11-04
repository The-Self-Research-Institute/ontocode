package self.research.ontology.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.InternalTokenVerifier;

import java.util.Map;

@RestController
@RequestMapping("/api/internal/users")
public class InternalUserController {

    private final UserRepository userRepository;
    private final InternalTokenVerifier internalToken;

    public InternalUserController(UserRepository userRepository, InternalTokenVerifier internalToken) {
        this.userRepository = userRepository;
        this.internalToken = internalToken;
    }

    @GetMapping("/email")
    public ResponseEntity<?> emailOf(@RequestParam String username,
                                     @RequestHeader(value = InternalTokenVerifier.HEADER, required = false) String token) {
        if (!internalToken.matches(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }
        return userRepository.findByUsername(username)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(Map.of("username", user.getUsername(), "email", user.getEmail())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
