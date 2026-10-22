package self.research.ontology.owlEditor.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class InternalApiAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalApiAuthInterceptor.class);
    static final String DEFAULT_INTERNAL_TOKEN = "ontocode-internal";

    @Value("${ontocode.internal.token:" + DEFAULT_INTERNAL_TOKEN + "}")
    private String expectedToken;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @PostConstruct
    void warnOnDefaultToken() {
        if (!desktopMode && DEFAULT_INTERNAL_TOKEN.equals(expectedToken)) {
            log.error("[Security] ontocode.internal.token is still the public default '{}'. Anyone who can reach "
                    + "the editor port can call its internal endpoints. Set ONTOCODE_INTERNAL_TOKEN to the same random "
                    + "secret for the editor, plugin service and reasoner worker.", DEFAULT_INTERNAL_TOKEN);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = request.getHeader("X-Ontocode-Internal-Token");
        if (expectedToken != null && expectedToken.equals(token)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"error\":\"Unauthorized\"}");
        return false;
    }
}
