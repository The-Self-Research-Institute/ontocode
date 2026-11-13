package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.repository.JdbcProjectAccessRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAccessServiceTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final ProjectAccessService service = new ProjectAccessService(new JdbcProjectAccessRepository(jdbc));

    @BeforeEach
    void clean() {
        jdbc.update("delete from project_member");
        jdbc.update("delete from project");
        jdbc.update("delete from ontology_file");
        jdbc.update("delete from app_user");
    }

    private void project(String businessId, String ownerId) {
        jdbc.update("insert into project (id, project_id, workspace_id, owner_id) values (?, ?, 'ws-1', ?)",
                "row-" + businessId, businessId, ownerId);
    }

    private void member(String businessId, int position, String userId, String email, String role) {
        jdbc.update("insert into project_member (project_row_id, position, user_id, email, role) values (?, ?, ?, ?, ?)",
                "row-" + businessId, position, userId, email, role);
    }

    private void file(String id, String parent, String ownerId, String ownerEmail) {
        jdbc.update("insert into ontology_file (id, project_id, owner_id, owner_email) values (?, ?, ?, ?)",
                id, parent, ownerId, ownerEmail);
    }

    @Test
    void aFileOfAProjectIsAccessibleThroughTheParentMembersAndIgnoresEmailCase() {
        project("proj-1", "u-owner");
        member("proj-1", 0, null, "barkat@x.org", "EDITOR");
        file("proj-1--file-9", "proj-1", "u-owner", "owner@x.org");

        assertTrue(service.hasProjectAccess("proj-1--file-9", "Barkat@x.org"));
        assertFalse(service.hasProjectAccess("proj-1--file-9", "stranger@x.org"));
    }

    @Test
    void anUnknownEmailIsStillRefused() {
        project("proj-1", "u-owner");

        assertFalse(service.hasProjectAccess("proj-1", " "));
        assertFalse(service.hasProjectAccess("proj-1", "nobody@x.org"));
        assertFalse(service.hasProjectAccess(null, "a@x.org"));
        assertFalse(service.hasProjectAccess(" ", "a@x.org"));
    }

    @Test
    void theOwnerIsRecognisedByUserIdEvenWhenTheEmailHasChanged() {
        project("proj-1", "u-1");

        assertTrue(service.hasProjectAccess("proj-1", "u-1", "new@x.org"));
    }

    @Test
    void aMemberIsRecognisedByUserId() {
        project("proj-1", "u-owner");
        member("proj-1", 0, "u-2", "other@x.org", "VIEWER");

        assertTrue(service.hasProjectAccess("proj-1", "u-2", "someone@x.org"));
    }

    @Test
    void aDifferentUserIdWithAnotherEmailIsRefused() {
        project("proj-1", "u-1");
        member("proj-1", 0, "u-1", "owner@x.org", "OWNER");

        assertFalse(service.hasProjectAccess("proj-1", "u-9", "intruder@x.org"));
    }

    @Test
    void aLegacyFileWithoutAContainerStillWorksByOwnerEmail() {
        file("legacy-1", null, null, "owner@x.org");

        assertTrue(service.hasProjectAccess("legacy-1", "u-1", "OWNER@x.org"));
        assertFalse(service.hasProjectAccess("legacy-1", "u-1", "other@x.org"));
    }

    @Test
    void aMemberWhoUploadedTheFileIsNotTheOwner() {
        project("proj-1", "u-owner");
        member("proj-1", 0, "u-owner", "owner@x.org", "OWNER");
        member("proj-1", 1, "u-editor", "editor@x.org", "EDITOR");
        file("proj-1", "proj-1", "u-editor", "editor@x.org");

        assertFalse(service.isOwner("proj-1", "u-editor", "editor@x.org"));
        assertFalse(service.isProjectOwner("proj-1", "editor@x.org"));
    }

    @Test
    void theRealOwnerStaysTheOwnerEvenAfterAMemberUploaded() {
        project("proj-1", "u-owner");
        member("proj-1", 0, "u-owner", "owner@x.org", "OWNER");
        member("proj-1", 1, "u-editor", "editor@x.org", "EDITOR");
        file("proj-1", "proj-1", "u-editor", "editor@x.org");

        assertTrue(service.isOwner("proj-1", "u-owner", "owner@x.org"));
        assertTrue(service.isProjectOwner("proj-1", "OWNER@x.org"));
    }

    @Test
    void theOwnerEmailOfAProjectComesFromTheOwnerAccountWhenTheMemberRowHasADifferentOne() {
        jdbc.update("insert into app_user (id, username, email) values ('u-owner', 'owner', 'owner@x.org')");
        project("proj-1", "u-owner");
        member("proj-1", 0, "u-owner", "old-owner@x.org", "OWNER");

        assertTrue(service.isProjectOwner("proj-1--file-3", "owner@x.org"));
    }

    @Test
    void withoutAWorkspaceProjectTheFileRecordOwnerStillCounts() {
        file("proj-1", null, "u-solo", "solo@x.org");

        assertTrue(service.isOwner("proj-1", "u-solo", "solo@x.org"));
        assertFalse(service.isOwner("proj-1", "u-other", "other@x.org"));
        assertFalse(service.isOwner("proj-1", " ", null));
    }

    @Test
    void aProjectWithMoreThanOneMemberIsShared() {
        project("proj-1", "u-owner");
        member("proj-1", 0, "u-owner", "owner@x.org", "OWNER");
        member("proj-1", 1, "u-editor", "editor@x.org", "EDITOR");

        assertTrue(service.isShared("proj-1--file-9"));
    }

    @Test
    void aProjectWithOnlyItsOwnerIsNotShared() {
        project("proj-1", "u-owner");
        member("proj-1", 0, "u-owner", "owner@x.org", "OWNER");

        assertFalse(service.isShared("proj-1"));
    }

    @Test
    void anUnknownOrBlankProjectIsNotShared() {
        assertFalse(service.isShared("missing"));
        assertFalse(service.isShared(" "));
        assertFalse(service.isShared(null));
    }

    @Test
    void projectExistsForContainersFilesAndFilesOfAContainer() {
        project("proj-1", "u-owner");
        file("legacy-1", null, "u", "u@x.org");

        assertTrue(service.projectExists("proj-1"));
        assertTrue(service.projectExists("proj-1--anything"));
        assertTrue(service.projectExists("legacy-1"));
        assertFalse(service.projectExists("proj-2--f"));
        assertFalse(service.projectExists(null));
    }

    @Test
    void visibleProjectsCoverOwnedMemberAndLegacyFiles() {
        project("proj-1", "u-1");
        project("proj-2", "u-9");
        member("proj-2", 0, "u-1", "u1@x.org", "VIEWER");
        project("proj-3", "u-9");
        file("legacy-1", null, "u-1", null);

        ProjectAccessService.Visibility visibility = service.visibleProjects("u-1", "u1@x.org");

        assertTrue(visibility.allows("proj-1--f"));
        assertTrue(visibility.allows("proj-2"));
        assertTrue(visibility.allows("legacy-1"));
        assertFalse(visibility.allows("proj-3--f"));
        assertFalse(visibility.allows(" "));
        assertEquals(0, service.visibleProjects(null, " ").projectIds().size());
    }
}
