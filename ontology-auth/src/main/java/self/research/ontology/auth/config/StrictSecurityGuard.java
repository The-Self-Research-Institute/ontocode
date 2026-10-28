package self.research.ontology.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.security.StrictSecurity;

@Component
public class StrictSecurityGuard {

    public StrictSecurityGuard(@Value("${ontocode.security.strict:false}") boolean strict,
                               @Value("${ontocode.desktop.mode:false}") boolean desktopMode,
                               @Value("${ontocode.internal.token:ontocode-internal}") String internalToken) {
        StrictSecurity.requireCustomInternalToken(strict && !desktopMode, internalToken);
    }
}
