package self.research.ontology.auth.service;

import self.research.ontology.auth.model.Workspace;

public final class ProjectRoleResolver {

    private ProjectRoleResolver() {
    }

    public static String resolve(boolean projectOwner, Workspace.WorkspaceRole workspaceRole, String projectRole) {
        if (projectOwner) {
            return "OWNER";
        }
        if ("DRAFT_EDITOR".equals(projectRole) || "VIEWER".equals(projectRole)) {
            return projectRole;
        }
        if (workspaceRole == Workspace.WorkspaceRole.OWNER || workspaceRole == Workspace.WorkspaceRole.ADMIN) {
            return "ADMIN";
        }
        return projectRole != null ? projectRole : "VIEWER";
    }
}
