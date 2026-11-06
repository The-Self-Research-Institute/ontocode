package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.repository.JdbcProjectAccessRepository;
import self.research.ontology.owlEditor.repository.JdbcWorkspaceRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOwnershipServiceTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final WorkspaceOwnershipService service =
            new WorkspaceOwnershipService(new JdbcProjectAccessRepository(jdbc), new JdbcWorkspaceRepository(jdbc));

    @BeforeEach
    void seed() {
        jdbc.update("delete from project_member");
        jdbc.update("delete from project");
        jdbc.update("delete from workspace_member");
        jdbc.update("delete from workspace");
        jdbc.update("delete from ontology_file");
        jdbc.update("insert into workspace (id, workspace_id, owner_id) values ('w1', 'ws-1', 'u-owner')");
        jdbc.update("insert into workspace_member (workspace_row_id, position, user_id, role) values ('w1', 0, 'u-admin', 'admin')");
        jdbc.update("insert into project (id, project_id, workspace_id, owner_id) values ('r1', 'proj-1', 'ws-1', 'u-owner')");
        jdbc.update("insert into project_member (project_row_id, position, user_id, role) values ('r1', 0, 'u-viewer', 'VIEWER')");
        jdbc.update("insert into project_member (project_row_id, position, user_id, role) values ('r1', 1, 'u-draft', 'draft_editor')");
        jdbc.update("insert into project_member (project_row_id, position, user_id, role) values ('r1', 2, 'u-editor', 'EDITOR')");
        jdbc.update("insert into project_member (project_row_id, position, user_id, role) values ('r1', 3, 'u-padmin', 'ADMIN')");
    }

    @Test
    void theWorkspaceOfAProjectOrOfItsFileIsResolved() {
        jdbc.update("insert into ontology_file (id, project_id, workspace_id) values ('proj-1--f', 'proj-1', null)");

        assertEquals(Optional.of("ws-1"), service.resolveWorkspaceIdForProject("proj-1"));
        assertEquals(Optional.of("ws-1"), service.resolveWorkspaceIdForProject("proj-1--f"));
        assertEquals(Optional.empty(), service.resolveWorkspaceIdForProject("proj-9--f"));
        assertEquals(Optional.empty(), service.resolveWorkspaceIdForProject(" "));
        assertEquals(Optional.of("ws-1"), service.resolveWorkspaceIdFromRequestPath("/api/x/proj-1--f/classes"));
    }

    @Test
    void ownersAndAdminsAreFoundThroughTheWorkspace() {
        assertTrue(service.isUserOwnerOfWorkspace("u-owner", " ws-1 "));
        assertFalse(service.isUserOwnerOfWorkspace("u-admin", "ws-1"));
        assertTrue(service.isUserOwnerOfProject("u-owner", "proj-1--f"));
        assertFalse(service.isUserOwnerOfProject("u-viewer", "proj-1--f"));
        assertTrue(service.isUserAdminOfWorkspace("u-admin", "ws-1"));
        assertTrue(service.isUserAdminOfProject("u-admin", "proj-1--f"));
        assertFalse(service.isUserAdminOfProject("u-owner", "proj-1--f"));
        assertTrue(service.isFreePlanUserOwner("u-owner", "/api/proj-1--f/x", null));
        assertTrue(service.isFreePlanUserOwner("u-owner", "/other", "ws-1"));
        assertFalse(service.isFreePlanUserOwner("u-viewer", "/api/proj-1--f/x", null));
    }

    @Test
    void projectRolesMatchIgnoringCase() {
        assertTrue(service.isViewerInProject("u-viewer", "proj-1--f"));
        assertFalse(service.isViewerInProject("u-editor", "proj-1"));
        assertTrue(service.isDraftEditorInProject("u-draft", "proj-1--f"));
        assertFalse(service.isDraftEditorInProject("u-viewer", "proj-1"));
    }

    @Test
    void publishingIsAllowedForOwnersWorkspaceAdminsAndProjectAdminsAndEditorsOnly() {
        assertTrue(service.canPublishToProject("u-owner", "proj-1--f"));
        assertTrue(service.canPublishToProject("u-admin", "proj-1--f"));
        assertTrue(service.canPublishToProject("u-editor", "proj-1--f"));
        assertTrue(service.canPublishToProject("u-padmin", "proj-1"));
        assertFalse(service.canPublishToProject("u-viewer", "proj-1--f"));
        assertFalse(service.canPublishToProject("u-draft", "proj-1--f"));
        assertFalse(service.canPublishToProject("stranger", "proj-1--f"));
        assertFalse(service.canPublishToProject(null, "proj-1"));
    }
}
