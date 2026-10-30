package self.research.ontology.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceAutoAddTest {

    private ProjectRepository projects;
    private ProjectService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        service = new ProjectService(projects, mock(WorkspaceRepository.class), mock(UserRepository.class),
                mock(SystemSettingsService.class), mock(FileMetadataRepository.class), mock(MongoTemplate.class),
                mock(GridFsTemplate.class));
        workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        workspace.setOwnerId("u-owner");
        workspace.addMember("u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.OWNER);
    }

    private Project project(String id, String visibility) {
        Project p = new Project();
        p.setProjectId(id);
        p.setName(id);
        p.setOwnerId("u-owner");
        p.setVisibility(visibility);
        p.addMember("u-owner", "owner", "owner@x.org", "OWNER");
        p.addMember("u-other", "other", "other@x.org", "EDITOR");
        return p;
    }

    private void existing(Project... list) {
        when(projects.findActiveByWorkspaceId("w1")).thenReturn(List.of(list));
    }

    @Test
    void newMemberIsAddedAsViewerToWorkspaceProject() {
        Project p = project("p1", "WORKSPACE");
        existing(p);

        service.autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.MEMBER);

        assertEquals("VIEWER", p.getMember("u-new").getRole());
        verify(projects).save(p);
    }

    @Test
    void newViewerIsAddedAsViewerToWorkspaceProject() {
        Project p = project("p1", "WORKSPACE");
        existing(p);

        service.autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.VIEWER);

        assertEquals("VIEWER", p.getMember("u-new").getRole());
    }

    @Test
    void newMemberIsNotAddedToSpecificPrivateOrLegacyProjects() {
        Project specific = project("p1", "SPECIFIC");
        Project priv = project("p2", "PRIVATE");
        Project legacy = project("p3", null);
        existing(specific, priv, legacy);

        int added = service.autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.MEMBER);

        assertEquals(0, added);
        assertNull(specific.getMember("u-new"));
        assertNull(priv.getMember("u-new"));
        assertNull(legacy.getMember("u-new"));
        verify(projects, never()).save(any());
    }

    @Test
    void newAdminIsAddedAsEditorToWorkspaceAndSpecificButNotPrivate() {
        Project all = project("p1", "WORKSPACE");
        Project specific = project("p2", "SPECIFIC");
        Project legacy = project("p3", null);
        Project priv = project("p4", "PRIVATE");
        existing(all, specific, legacy, priv);

        service.autoAddNewWorkspaceMember(workspace, "u-adm", "adm", "adm@x.org", Workspace.WorkspaceRole.ADMIN);

        assertEquals("EDITOR", all.getMember("u-adm").getRole());
        assertEquals("EDITOR", specific.getMember("u-adm").getRole());
        assertEquals("EDITOR", legacy.getMember("u-adm").getRole());
        assertNull(priv.getMember("u-adm"));
    }

    @Test
    void existingMemberOrOwnerIsNeverDuplicatedOrDowngraded() {
        Project p = project("p1", "WORKSPACE");
        p.addMember("u-ed", "ed", "ed@x.org", "EDITOR");
        existing(p);

        service.autoAddNewWorkspaceMember(workspace, "u-ed", "ed", "ed@x.org", Workspace.WorkspaceRole.MEMBER);
        service.autoAddNewWorkspaceMember(workspace, "u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.MEMBER);

        assertEquals("EDITOR", p.getMember("u-ed").getRole());
        assertEquals(1, p.getMembers().stream().filter(m -> "u-ed".equals(m.getUserId())).count());
        assertEquals(1, p.getMembers().stream().filter(m -> "u-owner".equals(m.getUserId())).count());
        verify(projects, never()).save(any());
    }

    @Test
    void aFailureOnOneProjectDoesNotBlockTheOthers() {
        Project bad = project("p1", "WORKSPACE");
        Project good = project("p2", "WORKSPACE");
        existing(bad, good);
        doThrow(new RuntimeException("boom")).when(projects).save(bad);

        int added = service.autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.MEMBER);

        assertEquals(1, added);
        assertEquals("VIEWER", good.getMember("u-new").getRole());
        verify(projects).save(good);
    }

    @Test
    void aFailureLoadingProjectsDoesNotThrow() {
        when(projects.findActiveByWorkspaceId("w1")).thenThrow(new RuntimeException("db down"));

        assertEquals(0, service.autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.MEMBER));
    }

    @Test
    void switchingToWorkspaceVisibilityAddsExistingActiveMembersAsViewersWithoutDowngrading() {
        workspace.addMember("u-ed", "ed", "ed@x.org", Workspace.WorkspaceRole.MEMBER);
        workspace.addMember("u-vw", "vw", "vw@x.org", Workspace.WorkspaceRole.VIEWER);
        workspace.addPendingMember("pending@x.org", Workspace.WorkspaceRole.MEMBER, "tok");
        Project p = project("p1", "SPECIFIC");
        p.addMember("u-ed", "ed", "ed@x.org", "EDITOR");

        int added = service.shareWithAllWorkspaceMembers(p, workspace);

        assertEquals("WORKSPACE", p.getVisibility());
        assertEquals(1, added);
        assertEquals("VIEWER", p.getMember("u-vw").getRole());
        assertEquals("EDITOR", p.getMember("u-ed").getRole());
        assertEquals(1, p.getMembers().stream().filter(m -> "u-owner".equals(m.getUserId())).count());
        assertTrue(p.getMembers().stream().noneMatch(m -> m.getUserId() == null));
        verify(projects).save(p);
    }
}
