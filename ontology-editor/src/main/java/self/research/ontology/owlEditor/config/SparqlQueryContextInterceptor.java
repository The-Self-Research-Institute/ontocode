package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

/**
 * Resolves the current user for SPARQL read scope (draft named graph inclusion).
 */
@Component
public class SparqlQueryContextInterceptor implements HandlerInterceptor {

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
        SparqlQueryContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        SparqlQueryContext.clear();
    }
}
