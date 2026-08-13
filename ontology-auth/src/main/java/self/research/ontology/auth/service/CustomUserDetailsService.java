package self.research.ontology.auth.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String normalizedIdentifier = identifier == null ? "" : identifier.trim();

        User authUser = userRepository.findByEmailIgnoreCase(normalizedIdentifier)
                .or(() -> userRepository.findByUsername(normalizedIdentifier))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + normalizedIdentifier));

        String[] authorities = buildAuthorities(authUser.getRoles());

        return org.springframework.security.core.userdetails.User.withUsername(authUser.getEmail())
                .password(authUser.getPassword())
                .authorities(authorities)
                .accountLocked(authUser.isAccountLocked())
                .disabled(!authUser.isEnabled())
                .build();
    }

    private String[] buildAuthorities(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return new String[]{"ROLE_USER"};
        }
        List<String> mapped = roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase())
                .collect(Collectors.toList());
        return mapped.toArray(new String[0]);
    }
}

