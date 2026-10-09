package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistorySyncServiceTest {

    private final HistorySyncService service = newService();

    private static HistorySyncService newService() {
        HistorySyncService service = new HistorySyncService(null, null, null);
        ReflectionTestUtils.setField(service, "conflictWindowHours", 24L);
        return service;
    }

    private static HistoryChange change(String id, String userId, String entityIRI, String oldValue,
                                        String newValue, LocalDateTime timestamp) {
        HistoryChange change = new HistoryChange("proj-1", "edit-" + id, userId, userId);
        change.setId(id);
        change.setEntityIRI(entityIRI);
        change.setOldValue(oldValue);
        change.setNewValue(newValue);
        change.setTimestamp(timestamp);
        return change;
    }

    @Test
    void sameUserEditsNeverConflict() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "v1", now);
        HistoryChange second = change("c2", "u1", "iri:A", "v1", "v2", now.plusMinutes(1));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void awareSequentialEditDoesNotConflict() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "v1", now);
        HistoryChange second = change("c2", "u2", "iri:A", "v1", "v2", now.plusMinutes(1));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void convergingToTheSameValueDoesNotConflict() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "same", now);
        HistoryChange second = change("c2", "u2", "iri:A", "base", "same", now.plusMinutes(1));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void genuineDivergenceConflictsAndCarriesPartnerData() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "mine", now);
        HistoryChange second = change("c2", "u2", "iri:A", "base", "theirs", now.plusMinutes(5));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertEquals(2, result.size());
        HistorySyncService.ConflictMatch forFirst = result.get("c1");
        assertEquals("c2", forFirst.partnerChangeId());
        assertEquals("u2", forFirst.partnerUserId());
        assertEquals("theirs", forFirst.partnerValue());
        HistorySyncService.ConflictMatch forSecond = result.get("c2");
        assertEquals("c1", forSecond.partnerChangeId());
        assertEquals("mine", forSecond.partnerValue());
    }

    @Test
    void revertedCandidateIsExcluded() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "mine", now);
        first.setReverted(true);
        HistoryChange second = change("c2", "u2", "iri:A", "base", "theirs", now.plusMinutes(5));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void draftCandidateIsExcluded() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "mine", now);
        first.setDraft(true);
        HistoryChange second = change("c2", "u2", "iri:A", "base", "theirs", now.plusMinutes(5));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void alreadyResolvedCandidateIsExcluded() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "mine", now);
        first.setResolvedAt(now);
        HistoryChange second = change("c2", "u2", "iri:A", "base", "theirs", now.plusMinutes(5));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void outOfWindowPairIsExcluded() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "mine", now);
        HistoryChange second = change("c2", "u2", "iri:A", "base", "theirs", now.plusHours(25));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertTrue(result.isEmpty());
    }

    @Test
    void mismatchedAnnotationPropertyOnTheSameEntityDoesNotConflict() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "mine", now);
        first.setAnnotationProperty("rdfs:label");
        HistoryChange second = change("c2", "u2", "iri:A", "base", "theirs", now.plusMinutes(5));
        second.setAnnotationProperty("rdfs:comment");

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, second));

        assertFalse(result.containsKey("c1"));
        assertFalse(result.containsKey("c2"));
    }

    @Test
    void onlyChronologicallyAdjacentEditsArePaired() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange first = change("c1", "u1", "iri:A", "base", "v1", now);
        HistoryChange middle = change("c2", "u2", "iri:A", "v1", "v2", now.plusMinutes(1));
        HistoryChange last = change("c3", "u3", "iri:A", "stale", "v3", now.plusMinutes(2));

        Map<String, HistorySyncService.ConflictMatch> result = service.computeConflicts(List.of(first, middle, last));

        assertFalse(result.containsKey("c1"));
        assertTrue(result.containsKey("c2"));
        assertTrue(result.containsKey("c3"));
        assertEquals("c2", result.get("c3").partnerChangeId());
    }
}
