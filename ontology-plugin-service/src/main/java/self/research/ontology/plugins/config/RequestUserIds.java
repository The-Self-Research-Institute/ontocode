package self.research.ontology.plugins.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestUserIds {

    private static final Logger log = LoggerFactory.getLogger(RequestUserIds.class);

    private RequestUserIds() {
    }

    public static String resolve(String clientSuppliedUserId) {
        if (clientSuppliedUserId == null || clientSuppliedUserId.isBlank()) {
            return clientSuppliedUserId;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object verified = attributes.getRequest().getAttribute(JwtAuthenticationFilter.AUTHENTICATED_USER_ID_ATTRIBUTE);
            if (verified instanceof String verifiedUserId && !verifiedUserId.isBlank()) {
                if (!verifiedUserId.equals(clientSuppliedUserId)) {
                    log.warn("Ignoring a client-supplied user id, using the authenticated one instead");
                }
                return verifiedUserId;
            }
        }
        Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
        boolean usable = signedIn != null && signedIn.isAuthenticated() && !(signedIn instanceof AnonymousAuthenticationToken)
                && signedIn.getName() != null && !signedIn.getName().isBlank();
        return usable ? signedIn.getName() : clientSuppliedUserId;
    }
}
