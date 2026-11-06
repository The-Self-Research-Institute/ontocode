package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.ProjectShare;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectShareRepository {

    Optional<ProjectShare> findByProjectId(String projectId);

    List<ProjectShare> findByOwnerEmail(String ownerEmail);

    List<ProjectShare> findBySharedWithEmailsContaining(String email);

    Optional<ProjectShare> findByShareLink(String shareLink);

    boolean existsByProjectId(String projectId);

    ProjectShare save(ProjectShare share);

    void delete(ProjectShare share);

    Optional<ProjectShare> addSharedEmail(String projectId, String email, String permission, Instant now);

    Optional<ProjectShare> removeSharedEmail(String projectId, String email, Instant now);
}
