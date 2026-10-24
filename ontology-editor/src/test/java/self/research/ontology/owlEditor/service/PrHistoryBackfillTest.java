package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrHistoryBackfillTest {

    private DraftPullRequestRepository prs;
    private HistorySyncService history;
    private PrHistoryBackfill backfill;

    @BeforeEach
    void setUp() {
        prs = mock(DraftPullRequestRepository.class);
        history = mock(HistorySyncService.class);
        backfill = new PrHistoryBackfill(prs, history);
    }

    private static DraftPullRequest pr(String id, DraftPullRequest.Status status, List<String> sets) {
        DraftPullRequest pr = new DraftPullRequest();
        pr.setId(id);
        pr.setProjectId("p1");
        pr.setStatus(status);
        pr.setPublishedChangeSetIds(sets);
        return pr;
    }

    @Test
    void aDryRunCountsTheRowsAndTagsNothing() {
        when(prs.findAll()).thenReturn(List.of(pr("pr1", DraftPullRequest.Status.APPROVED, List.of("cs1"))));
        when(history.countRowsToTag("p1", List.of("cs1"))).thenReturn(3L);

        var result = backfill.run(true);

        assertEquals(3L, result.get("rows"));
        verify(history, never()).tagRowsOfPr(any(), any(), any());
    }

    @Test
    void aRealRunTagsOnlyMergedPrsThatHaveRecordedChangeSets() {
        when(prs.findAll()).thenReturn(List.of(
                pr("pr1", DraftPullRequest.Status.APPROVED, List.of("cs1")),
                pr("pr2", DraftPullRequest.Status.REVERTED, List.of("cs2")),
                pr("pr3", DraftPullRequest.Status.OPEN, List.of("cs3")),
                pr("pr4", DraftPullRequest.Status.APPROVED, List.of())));
        when(history.tagRowsOfPr("p1", List.of("cs1"), "pr1")).thenReturn(2L);
        when(history.tagRowsOfPr("p1", List.of("cs2"), "pr2")).thenReturn(1L);

        var result = backfill.run(false);

        assertEquals(3L, result.get("rows"));
        assertEquals(2, result.get("prs"));
        verify(history, never()).tagRowsOfPr(any(), org.mockito.ArgumentMatchers.eq(List.of("cs3")), any());
    }

    @Test
    void startupRunsTheBackfillForRealUnlessSwitchedOff() {
        when(prs.findAll()).thenReturn(List.of(pr("pr1", DraftPullRequest.Status.APPROVED, List.of("cs1"))));
        backfill.onApplicationReady();
        verify(history).tagRowsOfPr("p1", List.of("cs1"), "pr1");

        HistorySyncService other = mock(HistorySyncService.class);
        PrHistoryBackfill off = new PrHistoryBackfill(prs, other);
        off.setOnStartup(false);
        off.onApplicationReady();
        verify(other, never()).tagRowsOfPr(any(), any(), any());
    }
}
