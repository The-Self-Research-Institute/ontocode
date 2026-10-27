package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.JwtClaimUtils;

@Service
public class RuleWriteAccess {

    public record Decision(boolean allowed, String reason) {
    }

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    private final WorkspaceOwnershipService ownership;

    public RuleWriteAccess(WorkspaceOwnershipService ownership) {
        this.ownership = ownership;
    }

    public Decision decide(String authorizationHeader, String remoteAddress, String projectId) {
        if (desktopMode && isLocal(remoteAddress)) {
            return new Decision(true, null);
        }
        String[] claims = JwtClaimUtils.extractPlanAndUserId(authorizationHeader);
        if (claims == null) {
            return new Decision(true, null);
        }
        String userId = claims[1];
        if (ownership.isViewerInProject(userId, projectId)) {
            return new Decision(false, "You have view-only access to this project, so you cannot change its rules.");
        }
        if (ownership.isDraftEditorInProject(userId, projectId)) {
            return new Decision(false, "Saved rules are shared by everyone in the project, so draft editors cannot change them.");
        }
        return new Decision(true, null);
    }

    private static boolean isLocal(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress) || "0:0:0:0:0:0:0:1".equals(remoteAddress) || "::1".equals(remoteAddress);
    }
}
