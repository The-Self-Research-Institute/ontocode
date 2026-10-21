package self.research.ontology.plugins.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.security.StrictSecurity;

@Component
public class StrictSecurityGuard {

    public StrictSecurityGuard(@Value("${ontocode.security.strict:false}") boolean strict,
                               @Value("${ontocode.desktop.mode:false}") boolean desktopMode,
                               @Value("${jwt.secret:}") String jwtSecret,
                               @Value("${ontocode.internal.token:ontocode-internal}") String internalToken) {
        boolean enforced = strict && !desktopMode;
        StrictSecurity.require(enforced, jwtSecret != null && !jwtSecret.isBlank(), "jwt.secret must be set");
        StrictSecurity.require(enforced, StrictSecurity.isCustomInternalToken(internalToken),
                "ontocode.internal.token must not be the public default");
    }
}
