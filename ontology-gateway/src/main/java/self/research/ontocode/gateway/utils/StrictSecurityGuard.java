package self.research.ontocode.gateway.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.security.StrictSecurity;

@Component
public class StrictSecurityGuard {

    public StrictSecurityGuard(@Value("${ontocode.security.strict:false}") boolean strict,
                               @Value("${jwt.secret:}") String jwtSecret,
                               @Value("${ontocode.gateway.require-jwt:true}") boolean requireJwt) {
        StrictSecurity.require(strict, jwtSecret != null && !jwtSecret.isBlank(), "jwt.secret must be set");
        StrictSecurity.require(strict, requireJwt, "ontocode.gateway.require-jwt must be true");
    }
}
