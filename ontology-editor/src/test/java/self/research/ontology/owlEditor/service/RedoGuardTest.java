package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedoGuardTest {

    private DraftPullRequestRepository prs;
    private RedoGuard guard;

    @BeforeEach
    void setUp() {
        prs = mock(DraftPullRequestRepository.class);
        guard = new RedoGuard(prs);
        when(prs.findByProjectIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
    }

    private static HistoryChange row(boolean draft, String changeSetId) {
        HistoryChange change = new HistoryChange("p", "e", "u", "u");
        change.setDraft(draft);
        change.setChangeSetId(changeSetId);
        return change;
    }

    private static DraftPullRequest pr(String id, DraftPullRequest.Status status, List<String> sets) {
        DraftPullRequest pr = new DraftPullRequest();
        pr.setId(id);
        pr.setStatus(status);
        pr.setPublishedChangeSetIds(sets);
        return pr;
    }

    @Test
    void anOrdinaryChangeCanBeRedone() {
        assertTrue(guard.refusal("p", List.of(row(false, "cs1"))).isEmpty());
    }

    @Test
    void aChangeFromADiscardedDraftCannotBeRedone() {
        HistoryChange discarded = row(true, "cs1");
        discarded.setDraftDiscarded(true);
        assertEquals(RedoGuard.DISCARDED, guard.refusal("p", List.of(discarded)).orElseThrow());
    }

    @Test
    void theDiscardedCheckAloneDoesNotLookAtPullRequests() {
        HistoryChange discarded = row(true, "cs1");
        discarded.setDraftDiscarded(true);
        assertEquals(RedoGuard.DISCARDED, guard.discardedRefusal(List.of(discarded)).orElseThrow());
        HistoryChange tagged = row(false, "cs1");
        tagged.setPublishedPrId("pr1");
        assertTrue(guard.discardedRefusal(List.of(tagged)).isEmpty());
    }

    @Test
    void aTaggedRowOfARevertedPrCannotBeRedone() {
        HistoryChange tagged = row(false, "cs1");
        tagged.setPublishedPrId("pr1");
        when(prs.findById("pr1")).thenReturn(Optional.of(pr("pr1", DraftPullRequest.Status.REVERTED, List.of("cs1"))));
        assertEquals(RedoGuard.PR_REVERTED, guard.refusal("p", List.of(tagged)).orElseThrow());
    }

    @Test
    void aPrThatIsHalfWayThroughBeingRevertedBlocksRedoToo() {
        HistoryChange tagged = row(false, "cs1");
        tagged.setPublishedPrId("pr1");
        DraftPullRequest partial = pr("pr1", DraftPullRequest.Status.APPROVED, List.of("cs1"));
        partial.setRevertStartedAt(java.time.Instant.now());
        when(prs.findById("pr1")).thenReturn(Optional.of(partial));
        assertEquals(RedoGuard.PR_REVERTED, guard.refusal("p", List.of(tagged)).orElseThrow());

        when(prs.findByProjectIdOrderByCreatedAtDesc(eq("p"))).thenReturn(List.of(partial));
        assertEquals(RedoGuard.PR_REVERTED, guard.refusal("p", List.of(row(false, "cs1"))).orElseThrow());
    }

    @Test
    void aTaggedRowOfAnApprovedPrIsNotBlocked() {
        HistoryChange tagged = row(false, "cs1");
        tagged.setPublishedPrId("pr1");
        when(prs.findById("pr1")).thenReturn(Optional.of(pr("pr1", DraftPullRequest.Status.APPROVED, List.of("cs1"))));
        assertTrue(guard.refusal("p", List.of(tagged)).isEmpty());
    }

    @Test
    void anUntaggedRowWhoseChangeSetBelongsToARevertedPrCannotBeRedone() {
        when(prs.findByProjectIdOrderByCreatedAtDesc(eq("p")))
                .thenReturn(List.of(pr("pr1", DraftPullRequest.Status.REVERTED, List.of("cs1", "cs2"))));
        assertEquals(RedoGuard.PR_REVERTED, guard.refusal("p", List.of(row(false, "cs2"))).orElseThrow());
        assertTrue(guard.refusal("p", List.of(row(false, "cs9"))).isEmpty());
    }

    @Test
    void draftRowsAreNotCheckedAgainstPullRequests() {
        when(prs.findByProjectIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(pr("pr1", DraftPullRequest.Status.REVERTED, List.of("cs1"))));
        assertTrue(guard.refusal("p", List.of(row(true, "cs1"))).isEmpty());
    }
}
