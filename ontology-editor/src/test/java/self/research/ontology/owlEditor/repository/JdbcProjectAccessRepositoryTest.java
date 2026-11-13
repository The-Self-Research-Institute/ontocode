package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectAccessRepositoryTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcProjectAccessRepository repository = new JdbcProjectAccessRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from project_member");
        jdbc.update("delete from project");
        jdbc.update("delete from workspace_member");
        jdbc.update("delete from workspace");
        jdbc.update("delete from ontology_file");
        jdbc.update("delete from app_user");
    }

    private void project(String rowId, String businessId, String workspaceId, String ownerId, boolean deleted) {
        jdbc.update("insert into project (id, project_id, workspace_id, owner_id, is_deleted) values (?, ?, ?, ?, ?)",
                rowId, businessId, workspaceId, ownerId, deleted);
    }

    private void member(String projectRowId, int position, String userId, String email, String role) {
        jdbc.update("insert into project_member (project_row_id, position, user_id, email, role) values (?, ?, ?, ?, ?)",
                projectRowId, position, userId, email, role);
    }

    private void file(String id, String parent, String ownerId, String ownerEmail, String workspaceId) {
        jdbc.update("insert into ontology_file (id, project_id, owner_id, owner_email, workspace_id) values (?, ?, ?, ?, ?)",
                id, parent, ownerId, ownerEmail, workspaceId);
    }

    @Test
    void theOwnerAndEveryMemberByUserIdOrCaseInsensitiveEmailHaveAccess() {
        project("r1", "proj-1", "ws-1", "owner-1", false);
        member("r1", 0, "owner-1", "Owner@X.org", "OWNER");
        member("r1", 1, "u2", "Editor@x.org", "EDITOR");
        member("r1", 2, null, "pending@x.org", "VIEWER");

        assertTrue(repository.hasAccess("proj-1--f", "proj-1", "owner-1", null));
        assertTrue(repository.hasAccess("proj-1--f", "proj-1", "u2", null));
        assertTrue(repository.hasAccess("proj-1--f", "proj-1", null, "EDITOR@X.ORG"));
        assertTrue(repository.hasAccess("proj-1--f", "proj-1", null, "Pending@x.org"));
        assertFalse(repository.hasAccess("proj-1--f", "proj-1", "stranger", "stranger@x.org"));
        assertFalse(repository.hasAccess("proj-2--f", "proj-2", "u2", "editor@x.org"));
    }

    @Test
    void aDeletedProjectGrantsNothing() {
        project("r1", "proj-1", "ws-1", "owner-1", true);
        member("r1", 0, "owner-1", "o@x.org", "OWNER");

        assertFalse(repository.hasAccess("proj-1--f", "proj-1", "owner-1", "o@x.org"));
        assertFalse(repository.projectExists("proj-1--f", "proj-1"));
    }

    @Test
    void aFileWithoutAProjectContainerIsAccessibleToItsOwnerOnly() {
        file("legacy-file", null, "u9", "Legacy@x.org", null);
        file("proj-5--f", "proj-5", null, "mine@x.org", null);

        assertTrue(repository.hasAccess("legacy-file", "legacy-file", "u9", null));
        assertTrue(repository.hasAccess("legacy-file", "legacy-file", null, "legacy@X.org"));
        assertFalse(repository.hasAccess("legacy-file", "legacy-file", "other", "other@x.org"));
        assertTrue(repository.hasAccess("proj-5--f", "proj-5", null, "mine@x.org"));
        assertTrue(repository.projectExists("legacy-file", "legacy-file"));
        assertTrue(repository.projectExists("proj-5--zzz", "proj-5"));
        assertFalse(repository.projectExists("proj-6--f", "proj-6"));
    }

    @Test
    void accessibleProjectsListEverythingTheUserCanSee() {
        project("r1", "proj-1", "ws-1", "u1", false);
        project("r2", "proj-2", "ws-1", "u9", false);
        project("r3", "proj-3", "ws-1", "u9", false);
        member("r2", 0, "u1", "u1@x.org", "VIEWER");
        member("r3", 0, "u9", "u9@x.org", "OWNER");
        file("legacy", null, "u1", null, null);
        file("proj-8--f", "proj-8", "u1", null, null);
        file("proj-9--f", "proj-9", "u9", null, null);

        ProjectAccessRepository.AccessibleProjects found = repository.accessibleProjects("u1", "u1@x.org");

        assertEquals(Set.of("legacy", "proj-8--f"), found.projectIds());
        assertEquals(Set.of("proj-1", "proj-2", "proj-8"), found.parentIds());
    }

    @Test
    void theWorkspaceProjectIsTheContainerWithMembersAndCarriesTheOwnerEmail() {
        jdbc.update("insert into app_user (id, username, email) values ('owner-1', 'own', 'Owner@x.org')");
        project("r1", "proj-1", "ws-1", "owner-1", false);
        member("r1", 0, "owner-1", "someone@x.org", "OWNER");
        member("r1", 1, "u2", "u2@x.org", "EDITOR");
        project("r2", "proj-2", "ws-1", "nobody", false);
        project("r3", "proj-3", "ws-1", "gone", false);
        member("r3", 0, "u7", "second@x.org", "OWNER");

        var withUser = repository.findWorkspaceProject("proj-1").orElseThrow();
        assertEquals("Owner@x.org", withUser.ownerEmail());
        assertEquals("owner-1", withUser.ownerId());
        assertEquals("ws-1", withUser.workspaceId());
        assertEquals(2, withUser.memberCount());
        assertEquals("second@x.org", repository.findWorkspaceProject("proj-3").orElseThrow().ownerEmail());
        assertTrue(repository.findWorkspaceProject("proj-2").isEmpty());
        assertTrue(repository.findWorkspaceProject("missing").isEmpty());
    }

    @Test
    void ownershipFallsBackToTheOwnerColumnsWhenThereAreNoMembers() {
        project("r2", "proj-2", "ws-1", "u1", false);
        file("proj-2--f", "proj-2", null, "file-owner@x.org", null);

        assertTrue(repository.ownsAnyRecord("proj-2--f", "proj-2", "u1", null));
        assertTrue(repository.ownsAnyRecord("proj-2--f", "proj-2", null, "FILE-OWNER@x.org"));
        assertFalse(repository.ownsAnyRecord("proj-2--f", "proj-2", "u3", "u3@x.org"));
    }

    @Test
    void memberRolesMatchIgnoringCaseAndOnlyForTheSameUser() {
        project("r1", "proj-1", "ws-1", "o", false);
        member("r1", 0, "u1", "u1@x.org", "viewer");
        member("r1", 1, "u2", "u2@x.org", "Editor");
        member("r1", 2, "u3", "u3@x.org", "DRAFT_EDITOR");

        assertTrue(repository.hasMemberRole("proj-1", "u1", List.of("VIEWER")));
        assertFalse(repository.hasMemberRole("proj-1", "u1", List.of("ADMIN", "EDITOR")));
        assertTrue(repository.hasMemberRole("proj-1", "u2", List.of("ADMIN", "EDITOR")));
        assertTrue(repository.hasMemberRole("proj-1", "u3", List.of("DRAFT_EDITOR")));
        assertFalse(repository.hasMemberRole("proj-1", "u4", List.of("VIEWER", "EDITOR", "DRAFT_EDITOR")));
        assertFalse(repository.hasMemberRole("proj-2", "u1", List.of("VIEWER")));
        assertFalse(repository.hasMemberRole("proj-1", "u1", List.of()));
    }

    @Test
    void workspaceAdminsAreFoundByUserId() {
        jdbc.update("insert into workspace (id, workspace_id, owner_id) values ('w1', 'ws-1', 'owner-1')");
        jdbc.update("insert into workspace_member (workspace_row_id, position, user_id, role) values ('w1', 0, 'u1', 'admin')");
        jdbc.update("insert into workspace_member (workspace_row_id, position, user_id, role) values ('w1', 1, 'u2', 'MEMBER')");

        assertTrue(repository.isWorkspaceAdmin("ws-1", "u1"));
        assertFalse(repository.isWorkspaceAdmin("ws-1", "u2"));
        assertFalse(repository.isWorkspaceAdmin("ws-2", "u1"));
    }

    @Test
    void theWorkspaceOfAProjectComesFromTheFileFirstThenTheContainer() {
        project("r1", "proj-1", " ws-container ", "o", false);
        file("proj-1--a", "proj-1", null, null, "ws-file");
        file("proj-1--b", "proj-1", null, null, null);

        assertEquals("ws-file", repository.findWorkspaceIdOfProject("proj-1--a", "proj-1").orElseThrow());
        assertEquals("ws-container", repository.findWorkspaceIdOfProject("proj-1--b", "proj-1").orElseThrow());
        assertTrue(repository.findWorkspaceIdOfProject("proj-9--a", "proj-9").isEmpty());
    }

    @Test
    void usersAreFoundByEmailIgnoringCase() {
        jdbc.update("insert into app_user (id, username, email) values ('u1', 'one', 'One@X.org')");

        assertEquals("u1", repository.findUserIdByEmail("one@x.ORG").orElseThrow());
        assertEquals("u1", repository.findUserIdByEmail("  One@X.org ").orElseThrow());
        assertTrue(repository.findUserIdByEmail("two@x.org").isEmpty());
        assertTrue(repository.findUserIdByEmail(" ").isEmpty());
    }
}
