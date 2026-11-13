package self.research.ontology.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.auth.EmbeddedDatabaseSpringTest;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class WorkspaceAndProjectServicesDbTest extends EmbeddedDatabaseSpringTest {

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    ProjectService projectService;

    @Autowired
    UserRepository users;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    ProjectRepository projects;

    @Autowired
    JdbcTemplate jdbc;

    User owner;
    User member;
    User outsider;

    @BeforeEach
    void setUp() {
        for (String table : List.of("project", "workspace", "file_metadata", "invitation", "app_user")) {
            jdbc.update("delete from " + table);
        }
        owner = user("owner");
        member = user("member");
        outsider = user("outsider");
    }

    private User user(String name) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setPassword("$2a$10$dummyhash");
        u.setEnabled(true);
        u.setRoles(Set.of("ROLE_USER"));
        return users.save(u);
    }

    private Project project(Workspace w, String name) {
        return projectService.createProject(w.getWorkspaceId(), owner.getId(), owner.getUsername(), owner.getEmail(), name, "d");
    }

    @Test
    void aNewWorkspaceIsOwnedByItsCreatorWithTheFreePlanLimits() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "  Development Workspace  ", " Workspace for development ");

        Workspace loaded = workspaceService.getWorkspace(w.getWorkspaceId()).orElseThrow();
        assertEquals("Development Workspace", loaded.getName());
        assertEquals("Workspace for development", loaded.getDescription());
        assertEquals(owner.getId(), loaded.getOwnerId());
        assertEquals("FREE", loaded.getSubscriptionPlan());
        assertEquals(3, loaded.getMaxMembers());
        assertFalse(loaded.isCollaborationEnabled());
        assertEquals(1, loaded.getMembers().size());
        assertEquals(WorkspaceRole.OWNER, loaded.getMember(owner.getId()).getRole());
    }

    @Test
    void workspaceNamesAreValidatedAndCreatedWithUniqueIds() {
        assertEquals("a", workspaceService.createWorkspace(owner.getId(), "A", "").getWorkspaceId());
        assertEquals("a-1", workspaceService.createWorkspace(owner.getId(), "A", "").getWorkspaceId());
        assertEquals(255, workspaceService.createWorkspace(owner.getId(), "x".repeat(255), null).getName().length());
        assertEquals("Special !@#$ name", workspaceService.createWorkspace(owner.getId(), "Special !@#$ name", null).getName());

        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(owner.getId(), "", null));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(owner.getId(), "   ", null));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(owner.getId(), "x".repeat(256), null));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(owner.getId(), "<script>", null));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(owner.getId(), "ok", "x".repeat(1001)));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(owner.getId(), "ok", "<b>bold</b>"));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace("missing-user", "ok", null));
    }

    @Test
    void anInjectionAttemptInTheNameIsStoredAsPlainTextAndNothingIsDropped() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "x'; drop table workspace; --", null);

        assertEquals("x'; drop table workspace; --", workspaces.findByWorkspaceId(w.getWorkspaceId()).orElseThrow().getName());
        assertEquals(1, jdbc.queryForObject("select count(*) from workspace", Integer.class));
    }

    @Test
    void workspacesAreListedForOwnersAndMembersOnlyAndAccessFollowsMembership() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        workspaceService.createWorkspace(outsider.getId(), "Other", null);
        workspaceService.addMember(w.getWorkspaceId(), member.getId(), WorkspaceRole.VIEWER);

        assertEquals(1, workspaceService.getAllUserWorkspaces(owner.getId()).size());
        assertEquals(1, workspaceService.getAllUserWorkspaces(member.getId()).size());
        assertTrue(workspaceService.hasAccess(w.getWorkspaceId(), owner.getId()));
        assertTrue(workspaceService.hasAccess(w.getWorkspaceId(), member.getId()));
        assertFalse(workspaceService.hasAccess(w.getWorkspaceId(), outsider.getId()));
        assertFalse(workspaceService.hasAccess("does-not-exist", owner.getId()));
        assertTrue(workspaceService.getWorkspace("does-not-exist").isEmpty());
        assertEquals(WorkspaceRole.OWNER, workspaceService.getMemberRole(w.getWorkspaceId(), owner.getId()));
        assertEquals(WorkspaceRole.VIEWER, workspaceService.getMemberRole(w.getWorkspaceId(), member.getId()));
        assertNull(workspaceService.getMemberRole(w.getWorkspaceId(), outsider.getId()));
    }

    @Test
    void aMemberWhoseUserIdWasStampedWrongIsFoundByEmailAndHealed() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        w.getMembers().add(new Workspace.WorkspaceMember("stale-id", "member", member.getEmail().toUpperCase(), WorkspaceRole.MEMBER));
        workspaces.save(w);

        assertTrue(workspaceService.hasAccess(w.getWorkspaceId(), member.getId()));

        assertEquals(member.getId(), workspaces.findByWorkspaceId(w.getWorkspaceId()).orElseThrow()
                .getMemberByEmail(member.getEmail()).getUserId());
    }

    @Test
    void membersCanBeAddedRemovedAndKeepTheirRolesAndTheOwnerCannotBeRemoved() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        String id = w.getWorkspaceId();

        workspaceService.addMember(id, member.getId(), WorkspaceRole.VIEWER);
        assertThrows(IllegalArgumentException.class, () -> workspaceService.addMember(id, member.getId(), WorkspaceRole.VIEWER));
        assertThrows(IllegalArgumentException.class, () -> workspaceService.addMember(id, "missing", WorkspaceRole.VIEWER));
        assertEquals(2, workspaces.findByWorkspaceId(id).orElseThrow().getMembers().size());

        Workspace changed = workspaces.findByWorkspaceId(id).orElseThrow();
        changed.updateMemberRole(member.getId(), WorkspaceRole.ADMIN);
        workspaces.save(changed);
        assertEquals(WorkspaceRole.ADMIN, workspaceService.getMemberRole(id, member.getId()));

        assertThrows(IllegalArgumentException.class, () -> workspaceService.removeMember(id, owner.getId()));
        workspaceService.removeMember(id, member.getId());
        assertEquals(1, workspaces.findByWorkspaceId(id).orElseThrow().getMembers().size());
        assertThrows(IllegalArgumentException.class, () -> workspaceService.removeMember(id, member.getId()));
    }

    @Test
    void aFreeWorkspaceStopsAcceptingMembersAtItsLimit() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Small", null);
        workspaceService.addMember(w.getWorkspaceId(), member.getId(), WorkspaceRole.VIEWER);
        workspaceService.addMember(w.getWorkspaceId(), outsider.getId(), WorkspaceRole.VIEWER);
        User fourth = user("fourth");

        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.addMember(w.getWorkspaceId(), fourth.getId(), WorkspaceRole.VIEWER));
        assertEquals(3, workspaces.findByWorkspaceId(w.getWorkspaceId()).orElseThrow().getMembers().size());
    }

    @Test
    void deletingAWorkspaceSoftDeletesItsProjectsAndHidesItFromActiveLists() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Doomed", null);
        Project p = project(w, "Doomed project");

        workspaceService.deleteWorkspace(w.getWorkspaceId(), owner.getId());

        assertTrue(workspaceService.getWorkspace(w.getWorkspaceId()).isEmpty());
        assertTrue(workspaceService.getWorkspaceIncludingDeleted(w.getWorkspaceId()).isPresent());
        assertTrue(workspaceService.getOwnedWorkspaces(owner.getId()).isEmpty());
        assertEquals(1, workspaceService.getDeletedUserWorkspaces(owner.getId()).size());
        assertTrue(projects.findByProjectId(p.getProjectId()).orElseThrow().getIsDeleted());

        workspaceService.restoreWorkspace(w.getWorkspaceId(), true, false);
        assertTrue(workspaceService.getWorkspace(w.getWorkspaceId()).isPresent());
        assertFalse(Boolean.TRUE.equals(projects.findByProjectId(p.getProjectId()).orElseThrow().getIsDeleted()));
    }

    @Test
    void projectsAreCreatedForWorkspaceMembersOnlyWithValidatedNames() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);

        Project p = project(w, "  Pizza Ontology  ");

        assertEquals("Pizza Ontology", p.getName());
        assertEquals("ACTIVE", p.getStatus());
        assertEquals(24, p.getId().length());
        assertEquals(1, p.getMembers().size());
        assertEquals("OWNER", p.getMember(owner.getId()).getRole());
        assertThrows(SecurityException.class, () -> projectService.createProject(w.getWorkspaceId(), outsider.getId(),
                outsider.getUsername(), outsider.getEmail(), "Nope", null));
        assertThrows(IllegalArgumentException.class, () -> project(w, ""));
        assertThrows(IllegalArgumentException.class, () -> project(w, "../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> project(w, "a/b"));
        assertThrows(IllegalArgumentException.class, () -> project(w, "<b>"));
        assertThrows(IllegalArgumentException.class, () -> project(w, "x".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> projectService.createProject(w.getWorkspaceId(), owner.getId(),
                owner.getUsername(), owner.getEmail(), "ok", "x".repeat(1001)));
        assertEquals(255, project(w, "y".repeat(255)).getName().length());
        assertNotEquals(p.getProjectId(), project(w, "Pizza Ontology").getProjectId());
    }

    @Test
    void projectsCanBeRenamedListedAndSoftDeletedByThoseWithPermission() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        workspaceService.addMember(w.getWorkspaceId(), member.getId(), WorkspaceRole.VIEWER);
        Project p = project(w, "Old name");

        Project renamed = projectService.updateProject(p.getProjectId(), owner.getId(), "New name", "new description");
        assertEquals("New name", projects.findByProjectId(p.getProjectId()).orElseThrow().getName());
        assertEquals("new description", renamed.getDescription());
        assertThrows(SecurityException.class, () -> projectService.updateProject(p.getProjectId(), outsider.getId(), "Hacked", null));
        assertEquals(1, projectService.getWorkspaceProjects(w.getWorkspaceId()).size());
        assertEquals(1, projectService.getProjectsByOwnerId(owner.getId()).size());

        assertThrows(SecurityException.class, () -> projectService.deleteProject(p.getProjectId(), outsider.getId()));
        assertThrows(SecurityException.class, () -> projectService.deleteProject(p.getProjectId(), member.getId()));
        projectService.deleteProject(p.getProjectId(), owner.getId());

        Project deleted = projects.findByProjectId(p.getProjectId()).orElseThrow();
        assertTrue(deleted.getIsDeleted());
        assertEquals("DELETED", deleted.getStatus());
        assertTrue(projects.findActiveByProjectId(p.getProjectId()).isEmpty());
        assertTrue(projectService.getWorkspaceProjects(w.getWorkspaceId()).isEmpty());
    }

    @Test
    void projectAccessFollowsMembershipRolesAndWorkspaceIsolation() {
        Workspace w1 = workspaceService.createWorkspace(owner.getId(), "One", null);
        Workspace w2 = workspaceService.createWorkspace(outsider.getId(), "Two", null);
        workspaceService.addMember(w1.getWorkspaceId(), member.getId(), WorkspaceRole.VIEWER);
        Project p1 = project(w1, "In one");
        Project p2 = projectService.createProject(w2.getWorkspaceId(), outsider.getId(), outsider.getUsername(),
                outsider.getEmail(), "In two", null);

        projectService.addMember(p1.getProjectId(), owner.getId(), member.getId(), member.getUsername(), member.getEmail(), "VIEWER");

        assertTrue(projectService.hasAccess(p1.getProjectId(), owner.getId()));
        assertTrue(projectService.hasAccess(p1.getProjectId(), member.getId()));
        assertFalse(projectService.hasAccess(p1.getProjectId(), outsider.getId()));
        assertFalse(projectService.hasAccess(p2.getProjectId(), owner.getId()));
        assertFalse(projectService.hasAccess(p2.getProjectId(), member.getId()));
        assertEquals(1, projectService.getProjectsSharedWithUser(member.getId()).size());
        assertEquals("VIEWER", projects.findByProjectId(p1.getProjectId()).orElseThrow().getMember(member.getId()).getRole());
        assertThrows(SecurityException.class, () -> projectService.updateProject(p1.getProjectId(), member.getId(), "viewer edit", null));
    }

    @Test
    void removingAWorkspaceMemberRemovesThemFromTheWorkspaceProjects() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        workspaceService.addMember(w.getWorkspaceId(), member.getId(), WorkspaceRole.VIEWER);
        Project p = project(w, "Shared");
        projectService.addMember(p.getProjectId(), owner.getId(), member.getId(), member.getUsername(), member.getEmail(), "EDITOR");
        assertTrue(projects.findByProjectId(p.getProjectId()).orElseThrow().hasMember(member.getId()));

        workspaceService.removeMember(w.getWorkspaceId(), member.getId());

        assertFalse(projects.findByProjectId(p.getProjectId()).orElseThrow().hasMember(member.getId()));
        assertFalse(projectService.hasAccess(p.getProjectId(), member.getId()));
    }

    @Test
    void projectFilesAreRenamedAndRemovedInTheProjectRecord() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        Project p = project(w, "Files");
        projectService.addFileMetadata(p.getProjectId(), owner.getId(),
                new Project.FileMetadataInfo("f1", "one.owl", 5L, "application/rdf+xml", "owl"));
        projectService.addFileMetadata(p.getProjectId(), owner.getId(),
                new Project.FileMetadataInfo("f2", "two.owl", 6L, "application/rdf+xml", "owl"));

        projectService.renameFile(p.getProjectId(), owner.getId(), "f1", "renamed");
        assertThrows(IllegalArgumentException.class, () -> projectService.renameFile(p.getProjectId(), owner.getId(), "f1", "two"));
        projectService.removeFile(p.getProjectId(), owner.getId(), "f2");

        Project loaded = projects.findByProjectId(p.getProjectId()).orElseThrow();
        assertEquals("renamed.owl", loaded.getFile("f1").getFileName());
        assertEquals(1, loaded.getActiveFiles().size());
        assertEquals(List.of("f1"), loaded.getFileIds());
    }

    @Test
    void permanentlyDeletingAProjectRemovesItsRowsAndChildren() {
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Team", null);
        Project p = project(w, "Temporary");
        projectService.addFileMetadata(p.getProjectId(), owner.getId(),
                new Project.FileMetadataInfo("f1", "one.owl", 5L, "application/rdf+xml", "owl"));

        projectService.hardDeleteProjectCompletely(p.getProjectId(), owner.getId());

        assertTrue(projects.findByProjectId(p.getProjectId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from project_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from project_file", Integer.class));
    }
}
