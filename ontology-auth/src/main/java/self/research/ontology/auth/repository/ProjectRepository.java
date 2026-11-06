package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.Project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    Optional<Project> findById(String id);

    Project save(Project project);

    void delete(Project project);

    void deleteAll(Iterable<? extends Project> projects);

    List<Project> findAllByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff);

    Optional<Project> findByProjectId(String projectId);

    List<Project> findAllByProjectId(String projectId);

    Optional<Project> findActiveByProjectId(String projectId);

    List<Project> findAllActiveByProjectId(String projectId);

    List<Project> findByWorkspaceId(String workspaceId);

    List<Project> findActiveByWorkspaceId(String workspaceId);

    List<Project> findByWorkspaceIdAndStatus(String workspaceId, String status);

    List<Project> findByOwnerId(String ownerId);

    List<Project> findByOwnerIdAndStatus(String ownerId, String status);

    List<Project> findByMembers_UserId(String userId);

    boolean existsByProjectId(String projectId);
}
