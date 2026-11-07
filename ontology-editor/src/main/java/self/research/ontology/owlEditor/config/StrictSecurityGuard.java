package self.research.ontology.owlEditor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.security.StrictSecurity;

@Component
public class StrictSecurityGuard {

    public StrictSecurityGuard(@Value("${ontocode.security.strict:false}") boolean strict,
                               @Value("${ontocode.desktop.mode:false}") boolean desktopMode,
                               @Value("${ontocode.editor.require-jwt:false}") boolean requireJwt,
                               @Value("${ontocode.internal.token:ontocode-internal}") String internalToken,
                               JwtVerifier jwtVerifier) {
        boolean enforced = strict && !desktopMode;
        StrictSecurity.require(enforced, jwtVerifier.configured(), "jwt.secret must be set");
        StrictSecurity.require(enforced, requireJwt, "ontocode.editor.require-jwt must be true");
        StrictSecurity.require(enforced, StrictSecurity.isCustomInternalToken(internalToken),
                "ontocode.internal.token must not be the public default");
    }
}
