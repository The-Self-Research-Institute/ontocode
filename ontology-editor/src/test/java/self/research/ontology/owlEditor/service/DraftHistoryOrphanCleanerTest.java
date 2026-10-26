package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftHistoryOrphanCleanerTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final DraftSessionRepository sessions = mock(DraftSessionRepository.class);
    private final DraftTrackingService drafts = mock(DraftTrackingService.class);
    private final HistorySyncService sync = mock(HistorySyncService.class);
    private final DraftHistoryOrphanCleaner cleaner = new DraftHistoryOrphanCleaner(mongo, sessions, drafts, sync);

    private void groups(Document... rows) {
        when(mongo.aggregate(any(Aggregation.class), eq(HistoryChange.class), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(rows), new Document()))
                .thenReturn(new AggregationResults<>(List.of(), new Document()));
    }

    private static Document group(String project, String user, int rows) {
        return new Document("_id", new Document("projectId", project).append("userId", user)).append("rows", rows);
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
