package self.research.ontology.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.config.OidcConfig;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service to handle OIDC user provisioning and mapping.
 * Auto-provisions users from OIDC providers if configured.
 */
@Service
@Slf4j
public class OidcUserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final OidcConfig oidcConfig;

    public OidcUserService(UserRepository userRepository, OidcConfig oidcConfig) {
        this.userRepository = userRepository;
        this.oidcConfig = oidcConfig;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);
        
        log.info("OIDC user authenticated: {}", oauth2User.getName());
        
        // Auto-provision user if enabled
        if (oidcConfig.isAutoProvision()) {
            provisionUser(oauth2User, userRequest.getClientRegistration().getRegistrationId());
        }
        
        return oauth2User;
    }
    
    /**
     * Provision or update user from OIDC provider.
     * Public so OidcController can call it on-demand if the user is not yet in MongoDB.
     */
    public void provisionUser(OAuth2User oauth2User, String provider) {
        // Extract user information from OAuth2User
        String email = extractEmail(oauth2User);
        String name = extractName(oauth2User);
        String username = extractUsername(oauth2User, email);
        
        log.debug("Provisioning OIDC user - email: {}, name: {}, username: {}, provider: {}", 
                  email, name, username, provider);
        
        // Check if user already exists by email
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    log.info("Creating new user from OIDC provider: {} - {}", provider, email);
                    User newUser = new User();
                    newUser.setUsername(username);
                    newUser.setEmail(email);
                    newUser.setName(name);
                    newUser.setEnabled(true);
                    newUser.setEmailVerified(true); // OIDC providers verify email
                    newUser.setOidcProvider(provider);
                    
                    // Set default roles from configuration
                    Set<String> roles = parseRoles(oidcConfig.getDefaultRoles());
                    newUser.setRoles(roles);
                    
                    return newUser;
                });
        
        // Update user info from OIDC provider (keep latest info)
        if (user.getOidcProvider() == null) {
            user.setOidcProvider(provider);
        }
        user.setName(name);
        user.setEmailVerified(true);
        user.setLastLogin(java.time.LocalDateTime.now());
        
        userRepository.save(user);
        log.info("OIDC user provisioned successfully: {}", email);
    }
    
    /**
     * Extract email from OAuth2User attributes
     */
    private String extractEmail(OAuth2User oauth2User) {
        // Try common email attribute names
        String email = oauth2User.getAttribute("email");
        if (email == null) {
            email = oauth2User.getAttribute("mail");
        }
        if (email == null) {
            email = oauth2User.getAttribute("preferred_username"); // Keycloak
        }
        
        if (email == null || email.isBlank()) {
            log.error("Could not extract email from OIDC user attributes: {}", oauth2User.getAttributes().keySet());
            throw new OAuth2AuthenticationException("Email not found in OIDC user attributes");
        }
        
        return email.toLowerCase().trim();
    }
    
    /**
     * Extract name from OAuth2User attributes
     */
    private String extractName(OAuth2User oauth2User) {
        String name = oauth2User.getAttribute("name");
        if (name == null || name.isBlank()) {
            String givenName = oauth2User.getAttribute("given_name");
            String familyName = oauth2User.getAttribute("family_name");
            if (givenName != null && familyName != null) {
                name = givenName + " " + familyName;
            } else if (givenName != null) {
                name = givenName;
            } else {
                name = oauth2User.getAttribute("nickname");
            }
        }
        return name != null ? name.trim() : extractEmail(oauth2User);
    }
    
    /**
     * Extract or generate username
     */
    private String extractUsername(OAuth2User oauth2User, String email) {
        // Try common username attribute names
        String username = oauth2User.getAttribute("preferred_username");
        if (username == null) {
            username = oauth2User.getAttribute("login");
        }
        if (username == null) {
            username = oauth2User.getAttribute("nickname");
        }
        
        // Fallback: use email prefix as username
        if (username == null || username.isBlank()) {
            username = email.split("@")[0];
        }
        
        // Ensure username is unique
        String finalUsername = username.toLowerCase().trim();
        if (userRepository.findByUsername(finalUsername).isPresent()) {
            // Append random suffix if username exists
            finalUsername = finalUsername + "_" + System.currentTimeMillis() % 10000;
        }
        
        return finalUsername;
    }
    
    /**
     * Parse roles from comma-separated string
     */
    private Set<String> parseRoles(String rolesString) {
        if (rolesString == null || rolesString.isBlank()) {
            return new HashSet<>(Set.of("ROLE_USER"));
        }
        return Arrays.stream(rolesString.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
