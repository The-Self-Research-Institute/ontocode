package self.research.ontology.auth.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.InvitationRepository;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceAutoAddTest {

    @Test
    void addingAMemberDirectlyRunsTheSharedProjectAutoAdd() {
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        WorkspaceService service = new WorkspaceService(workspaces, users, mock(ProjectRepository.class),
                mock(FileMetadataRepository.class), mock(PlanFeatureConfigService.class),
                mock(SystemSettingsService.class), mock(InvitationRepository.class), projectService);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        workspace.setOwnerId("u-owner");
        workspace.addMember("u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.OWNER);
        when(workspaces.findByWorkspaceId("w1")).thenReturn(Optional.of(workspace));
        User user = new User();
        user.setId("u-new");
        user.setUsername("new");
        user.setEmail("new@x.org");
        when(users.findById("u-new")).thenReturn(Optional.of(user));

        service.addMember("w1", "u-new", Workspace.WorkspaceRole.MEMBER);

        verify(projectService).autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.MEMBER);
    }
}
