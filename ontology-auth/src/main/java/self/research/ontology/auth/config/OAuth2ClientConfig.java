package self.research.ontology.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "oidc.enabled", havingValue = "true")
public class OAuth2ClientConfig {

    @Value("${oidc.base-url}")
    private String baseUrl;

    @Value("${oidc.providers.keycloak.enabled:false}")
    private boolean keycloakEnabled;

    @Value("${oidc.providers.keycloak.client-id:}")
    private String keycloakClientId;

    @Value("${oidc.providers.keycloak.client-secret:}")
    private String keycloakClientSecret;

    @Value("${oidc.providers.keycloak.issuer-uri:}")
    private String keycloakIssuerUri;

    @Bean
    @ConditionalOnProperty(name = "oidc.providers.keycloak.enabled", havingValue = "true")
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        if (keycloakEnabled && !keycloakClientId.isEmpty() && !keycloakIssuerUri.isEmpty()) {
            // Manually construct OAuth2 endpoints from issuer URI
            String authorizationUri = keycloakIssuerUri + "/protocol/openid-connect/auth";
            String tokenUri = keycloakIssuerUri + "/protocol/openid-connect/token";
            String userInfoUri = keycloakIssuerUri + "/protocol/openid-connect/userinfo";
            String jwkSetUri = keycloakIssuerUri + "/protocol/openid-connect/certs";
            
            ClientRegistration keycloakRegistration = ClientRegistration
                    .withRegistrationId("keycloak")
                    .clientId(keycloakClientId)
                    .clientSecret(keycloakClientSecret)
                    .scope("openid", "profile", "email")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(baseUrl + "/login/oauth2/code/keycloak")
                    .authorizationUri(authorizationUri)
                    .tokenUri(tokenUri)
                    .userInfoUri(userInfoUri)
                    .jwkSetUri(jwkSetUri)
                    .userNameAttributeName("preferred_username")
                    .clientName("Keycloak")
                    .build();
            registrations.add(keycloakRegistration);
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
