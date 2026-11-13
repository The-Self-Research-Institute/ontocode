package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository.DraftGroup;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftHistoryOrphanCleanerTest {

    private final HistoryChangeRepository history = mock(HistoryChangeRepository.class);
    private final DraftSessionRepository sessions = mock(DraftSessionRepository.class);
    private final DraftTrackingService drafts = mock(DraftTrackingService.class);
    private final HistorySyncService sync = mock(HistorySyncService.class);
    private final DraftHistoryOrphanCleaner cleaner = new DraftHistoryOrphanCleaner(history, sessions, drafts, sync);

    private void groups(DraftGroup... rows) {
        when(history.countLiveDraftGroups()).thenReturn(List.of(rows));
    }

    private static DraftGroup group(String project, String user, int rows) {
        return new DraftGroup(project, user, rows);
    }

    @Test
    void aDryRunReportsOrphansWithoutMarkingAnything() {
        groups(group("p1", "dead", 3), group("p1", "live", 2));
        when(sessions.findByProjectIdAndUserId("p1", "dead")).thenReturn(Optional.empty());
        when(sessions.findByProjectIdAndUserId("p1", "live")).thenReturn(Optional.of(new DraftSession()));
        when(drafts.getUnappliedDraftsForUser("p1", "dead")).thenReturn(List.of());

        Map<String, Object> result = cleaner.run(true);

        assertEquals(1, result.get("orphanGroups"));
        assertEquals(3L, result.get("orphanRows"));
        assertEquals(0L, result.get("marked"));
        verify(sync, never()).markDraftsDiscarded("p1", "dead");
    }

    @Test
    void applyingMarksOnlyTheOrphanGroups() {
        groups(group("p1", "dead", 3), group("p1", "live", 2));
        when(sessions.findByProjectIdAndUserId("p1", "dead")).thenReturn(Optional.empty());
        when(sessions.findByProjectIdAndUserId("p1", "live")).thenReturn(Optional.of(new DraftSession()));
        when(drafts.getUnappliedDraftsForUser("p1", "dead")).thenReturn(List.of());
        when(sync.markDraftsDiscarded("p1", "dead")).thenReturn(3L);

        Map<String, Object> result = cleaner.run(false);

        assertEquals(3L, result.get("marked"));
        verify(sync).markDraftsDiscarded("p1", "dead");
        verify(sync, never()).markDraftsDiscarded("p1", "live");
    }
}
