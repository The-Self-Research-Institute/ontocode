package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

@Component
public class SparqlQueryContextInterceptor implements HandlerInterceptor {

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CallerIdentity caller = CallerIdentity.of(request);
        String tokenUserId = caller.userIdOr(caller.email());
        SparqlQueryContext.setUserId(desktopMode ? desktopUserId(request, tokenUserId) : tokenUserId);
        SparqlQueryContext.setWantsDraft("true".equalsIgnoreCase(request.getParameter("draft")));
        return true;
    }

    private static String desktopUserId(HttpServletRequest request, String tokenUserId) {
        String userId = request.getParameter("userId");
        if (userId == null || userId.isBlank()) {
            userId = request.getHeader("X-Ontocode-User-Id");
        }
        if (userId == null || userId.isBlank()) {
            userId = tokenUserId;
        }
        return userId == null || userId.isBlank() ? DESKTOP_USER_ID : userId;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        SparqlQueryContext.clear();
    }
}
