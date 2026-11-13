package self.research.ontology.auth.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectRoleResolverTest {

    private static String resolve(WorkspaceRole ws, String project) {
        return ProjectRoleResolver.resolve(false, ws, project);
    }

    @Test
    void projectOwnerIsAlwaysOwner() {
        assertEquals("OWNER", ProjectRoleResolver.resolve(true, null, null));
        assertEquals("OWNER", ProjectRoleResolver.resolve(true, WorkspaceRole.ADMIN, "VIEWER"));
        assertEquals("OWNER", ProjectRoleResolver.resolve(true, WorkspaceRole.MEMBER, "DRAFT_EDITOR"));
    }

    @Test
    void workspaceOwnerAndAdminAreAdminUnlessRestrictedOnTheProject() {
        for (WorkspaceRole ws : new WorkspaceRole[] {WorkspaceRole.OWNER, WorkspaceRole.ADMIN}) {
            assertEquals("ADMIN", resolve(ws, null));
            assertEquals("ADMIN", resolve(ws, "EDITOR"));
            assertEquals("ADMIN", resolve(ws, "ADMIN"));
            assertEquals("DRAFT_EDITOR", resolve(ws, "DRAFT_EDITOR"));
            assertEquals("VIEWER", resolve(ws, "VIEWER"));
        }
    }

    @Test
    void plainMembersGetTheirProjectRole() {
        for (String role : new String[] {"ADMIN", "EDITOR", "DRAFT_EDITOR", "VIEWER"}) {
            assertEquals(role, resolve(WorkspaceRole.MEMBER, role));
            assertEquals(role, resolve(null, role));
        }
    }

    @Test
    void nonMembersDefaultToViewer() {
        assertEquals("VIEWER", resolve(null, null));
        assertEquals("VIEWER", resolve(WorkspaceRole.MEMBER, null));
    }
}
