package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RedoGuard {

    static final String DISCARDED = "That draft was discarded, so this change can't be redone";
    static final String PR_REVERTED = "This was undone by reverting a pull request. The pull request's author can restore it from the pull request";

    private final DraftPullRequestRepository pullRequests;

    @Autowired
    public RedoGuard(DraftPullRequestRepository pullRequests) {
        this.pullRequests = pullRequests;
    }

    static boolean revertedOrBeingReverted(DraftPullRequest pr) {
        return pr.getStatus() == DraftPullRequest.Status.REVERTED || pr.getRevertStartedAt() != null;
    }

    public Optional<String> discardedRefusal(List<HistoryChange> entries) {
        for (HistoryChange entry : entries) {
            if (entry.isDraftDiscarded()) {
                return Optional.of(DISCARDED);
            }
        }
        return Optional.empty();
    }

    public Optional<String> refusal(String projectId, List<HistoryChange> entries) {
        Optional<String> discarded = discardedRefusal(entries);
        if (discarded.isPresent()) {
            return discarded;
        }
        List<DraftPullRequest> reverted = null;
        for (HistoryChange entry : entries) {
            if (entry.isDraft()) {
                continue;
            }
            if (entry.getPublishedPrId() != null) {
                Optional<DraftPullRequest> pr = pullRequests.findById(entry.getPublishedPrId());
                if (pr.isPresent() && revertedOrBeingReverted(pr.get())) {
                    return Optional.of(PR_REVERTED);
                }
            } else if (entry.getChangeSetId() != null) {
                if (reverted == null) {
                    reverted = pullRequests.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                            .filter(RedoGuard::revertedOrBeingReverted).toList();
                }
                for (DraftPullRequest pr : reverted) {
                    if (pr.getPublishedChangeSetIds() != null && pr.getPublishedChangeSetIds().contains(entry.getChangeSetId())) {
                        return Optional.of(PR_REVERTED);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
