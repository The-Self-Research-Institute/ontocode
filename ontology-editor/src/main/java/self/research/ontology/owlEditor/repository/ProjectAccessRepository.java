package self.research.ontology.owlEditor.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface ProjectAccessRepository {

    record WorkspaceProject(String id, String projectId, String workspaceId, String ownerId, String ownerEmail,
                            int memberCount) {
    }

    record AccessibleProjects(Set<String> projectIds, Set<String> parentIds) {
    }

    boolean projectExists(String projectId, String parentProjectId);

    boolean hasAccess(String projectId, String parentProjectId, String userId, String email);

    AccessibleProjects accessibleProjects(String userId, String email);

    Optional<WorkspaceProject> findWorkspaceProject(String parentProjectId);

    boolean ownsAnyRecord(String projectId, String parentProjectId, String userId, String email);

    boolean hasMemberRole(String parentProjectId, String userId, Collection<String> roles);

    boolean isWorkspaceAdmin(String workspaceId, String userId);

    Optional<String> findWorkspaceIdOfProject(String projectId, String parentProjectId);

    Optional<String> findUserIdByEmail(String email);
}
