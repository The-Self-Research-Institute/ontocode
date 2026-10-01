package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Arrays;
import java.util.Set;

@Component
public class EditorIdentityConfig {

    private static final Logger log = LoggerFactory.getLogger(EditorIdentityConfig.class);
    private static final Set<String> DEPLOYED_PROFILES = Set.of("docker", "production", "prod");

    private final Environment environment;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ontocode.editor.require-jwt:false}")
    private boolean requireJwt;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public EditorIdentityConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void configure() {
        if (desktopMode) {
            return;
        }
        boolean deployed = Arrays.stream(environment.getActiveProfiles()).anyMatch(DEPLOYED_PROFILES::contains);
        if (jwtSecret == null || jwtSecret.isBlank()) {
            if (deployed || requireJwt) {
                throw new IllegalStateException("jwt.secret must be set: without it no request identity or project "
                        + "access can be verified. Set JWT_SECRET for the editor service.");
            }
            log.warn("[Security] jwt.secret is not set; request identities are not verified. Use this only for "
                    + "local development.");
            return;
        }
        JwtIdentityExtractor.requireSignature(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret)));
        if (!requireJwt) {
            log.warn("[Security] ontocode.editor.require-jwt=false: requests without a token are allowed. Tokens "
                    + "that are sent are still verified and checked for project access.");
        }
    }
}
