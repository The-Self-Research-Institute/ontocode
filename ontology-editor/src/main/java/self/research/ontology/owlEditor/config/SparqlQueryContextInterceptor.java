package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

/**
 * Resolves the current user for SPARQL read scope (draft named graph inclusion).
 */
@Component
public class SparqlQueryContextInterceptor implements HandlerInterceptor {

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getParameter("userId");
        if (userId == null || userId.isBlank()) {
            userId = request.getHeader("X-Ontocode-User-Id");
        }
        if (userId == null || userId.isBlank()) {
            String auth = request.getHeader("Authorization");
            String[] planUser = JwtClaimUtils.extractPlanAndUserId(auth);
            if (planUser != null && planUser.length > 1) {
                userId = planUser[1];
            }
        }
        if ((userId == null || userId.isBlank()) && desktopMode) {
            userId = DESKTOP_USER_ID;
        }
        SparqlQueryContext.setUserId(userId);
        SparqlQueryContext.setWantsDraft("true".equalsIgnoreCase(request.getParameter("draft")));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        SparqlQueryContext.clear();
    }
}
