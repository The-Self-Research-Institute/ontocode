package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.DraftPullRequest.Status;

import java.util.List;
import java.util.Optional;

@Repository
public interface DraftPullRequestRepository extends MongoRepository<DraftPullRequest, String> {

    List<DraftPullRequest> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<DraftPullRequest> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, Status status);

    Optional<DraftPullRequest> findByProjectIdAndAuthorIdAndStatus(String projectId, String authorId, Status status);

    long countByProjectIdAndStatus(String projectId, Status status);
}
