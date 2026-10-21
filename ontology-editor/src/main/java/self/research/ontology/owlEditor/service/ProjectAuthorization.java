package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.config.CallerIdentity;

import java.util.Optional;

@Component
public class ProjectAuthorization {

    private final ProjectAccessService accessService;
    private final boolean desktopMode;

    public ProjectAuthorization(ProjectAccessService accessService,
                                @Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        this.accessService = accessService;
        this.desktopMode = desktopMode;
    }

    public boolean isDesktopMode() {
        return desktopMode;
    }

    public Optional<AccessDenial> unauthenticated(HttpServletRequest request) {
        return unauthenticated(CallerIdentity.of(request));
    }

    public Optional<AccessDenial> unauthenticated(CallerIdentity caller) {
        return desktopMode || caller.signedIn() ? Optional.empty() : Optional.of(AccessDenial.unauthenticated());
    }

    public Optional<AccessDenial> noAccessTo(HttpServletRequest request, String projectId) {
        return noAccessTo(CallerIdentity.of(request), projectId);
    }

    public Optional<AccessDenial> noAccessTo(CallerIdentity caller, String projectId) {
        Optional<AccessDenial> unauthenticated = unauthenticated(caller);
        if (desktopMode || unauthenticated.isPresent()) {
            return unauthenticated;
        }
        boolean allowed = caller.userId() != null
                ? accessService.hasProjectAccess(projectId, caller.userId(), caller.email())
                : accessService.hasProjectAccess(projectId, caller.email());
        return allowed ? Optional.empty() : Optional.of(AccessDenial.forbidden("You do not have access to this project"));
    }

    public Optional<AccessDenial> notOwnerOf(HttpServletRequest request, String projectId) {
        return notOwnerOf(CallerIdentity.of(request), projectId);
    }

    public Optional<AccessDenial> notOwnerOf(CallerIdentity caller, String projectId) {
        Optional<AccessDenial> unauthenticated = unauthenticated(caller);
        if (desktopMode || unauthenticated.isPresent()) {
            return unauthenticated;
        }
        return accessService.isOwner(projectId, caller.userId(), caller.email())
                ? Optional.empty()
                : Optional.of(AccessDenial.forbidden("Only the project owner can do this"));
    }
}
