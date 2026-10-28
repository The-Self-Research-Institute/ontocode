package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Optional;

@Component
public class RuleRunAccess {

    public record Decision(int status, String userId) {
        public boolean allowed() {
            return status == 200;
        }
    }

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Autowired(required = false)
    private ProjectAccessService projectAccess;

    public RuleRunAccess() {
    }

    public RuleRunAccess(ProjectAccessService projectAccess, boolean desktopMode) {
        this.projectAccess = projectAccess;
        this.desktopMode = desktopMode;
    }

    public Optional<String> identity(HttpServletRequest request) {
        Optional<String> userId = JwtIdentityExtractor.extractUserId(request);
        if (userId.isPresent() && !userId.get().isBlank()) {
            return userId;
        }
        return localDesktop(request) ? Optional.of(JwtIdentityExtractor.DESKTOP_USER) : Optional.empty();
    }

    public Decision check(HttpServletRequest request, String projectId) {
        Optional<String> userId = identity(request);
        if (userId.isEmpty()) {
            return new Decision(401, null);
        }
        if (projectAccess != null && !localDesktop(request)) {
            Optional<String> email = JwtIdentityExtractor.extractEmail(request);
            if (email.isEmpty() || !projectAccess.hasProjectAccess(projectId, email.get())) {
                return new Decision(403, userId.get());
            }
        }
        return new Decision(200, userId.get());
    }

    private boolean localDesktop(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return desktopMode && ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote));
    }
}
