package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.DraftPullRequest.Status;

import java.util.List;
import java.util.Optional;

public interface DraftPullRequestRepository {

    DraftPullRequest save(DraftPullRequest request);

    Optional<DraftPullRequest> findById(String id);

    List<DraftPullRequest> findAll();

    void deleteById(String id);

    List<DraftPullRequest> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<DraftPullRequest> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, Status status);

    Optional<DraftPullRequest> findByProjectIdAndAuthorIdAndStatus(String projectId, String authorId, Status status);

    long countByProjectIdAndStatus(String projectId, Status status);
}
