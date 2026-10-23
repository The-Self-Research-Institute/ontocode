package self.research.ontology.reasoner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.security.StrictSecurity;

@Component
public class StrictSecurityGuard {

    public StrictSecurityGuard(@Value("${ontocode.security.strict:false}") boolean strict,
                               @Value("${ontocode.internal.token:ontocode-internal}") String internalToken) {
        StrictSecurity.require(strict, StrictSecurity.isCustomInternalToken(internalToken),
                "ontocode.internal.token must not be the public default");
    }
}
