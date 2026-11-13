package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.repository.ProjectAccessRepository;

import java.util.Set;

@Service
public class ProjectAccessService {

    private static final String COMPOSITE_PROJECT_SEPARATOR = "--";

    private final ProjectAccessRepository access;

    public ProjectAccessService(ProjectAccessRepository access) {
        this.access = access;
    }

    static String parentOf(String projectId) {
        int sep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
        return sep > 0 ? projectId.substring(0, sep) : projectId;
    }

    public boolean projectExists(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        return access.projectExists(projectId, parentOf(projectId));
    }

    public boolean hasProjectAccess(String projectId, String email) {
        return hasProjectAccess(projectId, null, email);
    }

    public boolean hasProjectAccess(String projectId, String userId, String email) {
        boolean noUserId = userId == null || userId.isBlank();
        if (projectId == null || projectId.isBlank() || ((email == null || email.isBlank()) && noUserId)) {
            return false;
        }
        return access.hasAccess(projectId, parentOf(projectId), userId, email == null ? null : email.trim());
    }

    public record Visibility(Set<String> projectIds, Set<String> parentIds) {

        public boolean allows(String projectId) {
            if (projectId == null || projectId.isBlank()) {
                return false;
            }
            if (projectIds.contains(projectId)) {
                return true;
            }
            int sep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
            return parentIds.contains(sep > 0 ? projectId.substring(0, sep) : projectId);
        }
    }

    public Visibility visibleProjects(String userId, String email) {
        boolean noUserId = userId == null || userId.isBlank();
        boolean noEmail = email == null || email.isBlank();
        if (noUserId && noEmail) {
            return new Visibility(Set.of(), Set.of());
        }
        ProjectAccessRepository.AccessibleProjects found = access.accessibleProjects(userId, noEmail ? null : email.trim());
        return new Visibility(found.projectIds(), found.parentIds());
    }

    public boolean isOwner(String projectId, String userId, String email) {
        boolean noUserId = userId == null || userId.isBlank();
        boolean noEmail = email == null || email.isBlank();
        if (projectId == null || projectId.isBlank() || (noUserId && noEmail)) {
            return false;
        }
        String parent = parentOf(projectId);
        var workspaceRecord = access.findWorkspaceProject(parent);
        if (workspaceRecord.isPresent()) {
            var record = workspaceRecord.get();
            if (!noUserId && userId.equals(record.ownerId())) {
                return true;
            }
            return !noEmail && record.ownerEmail() != null && email.equalsIgnoreCase(record.ownerEmail());
        }
        return access.ownsAnyRecord(projectId, parent, userId, noEmail ? null : email.trim());
    }

    public boolean isProjectOwner(String projectId, String email) {
        return isOwner(projectId, null, email);
    }

    public boolean isShared(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        return access.findWorkspaceProject(parentOf(projectId))
                .map(record -> record.memberCount() > 1)
                .orElse(false);
    }
}
