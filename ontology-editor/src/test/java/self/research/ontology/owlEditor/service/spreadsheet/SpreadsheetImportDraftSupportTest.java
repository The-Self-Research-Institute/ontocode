package self.research.ontology.owlEditor.service.spreadsheet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.JdbcDraftChangeRepository;
import self.research.ontology.owlEditor.repository.SpreadsheetImportRecordRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpreadsheetImportDraftSupportTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final SpreadsheetImportRecordRepository records = mock(SpreadsheetImportRecordRepository.class);
    private final HistoryChangeRepository history = mock(HistoryChangeRepository.class);
    private final DraftChangeRepository drafts = new JdbcDraftChangeRepository(jdbc, TestDatabase.transactions());
    private final SpreadsheetImportDraftSupport support = new SpreadsheetImportDraftSupport(records, history, drafts);

    @BeforeEach
    void clean() {
        jdbc.update("delete from draft_change");
        jdbc.update("delete from draft_change_parked");
    }

    private DraftChange draft(String project, String user, String session, boolean applied) {
        DraftChange change = new DraftChange(project, user, user, "createClass", Map.of("iri", "http://x#A"));
        change.setSessionId(session);
        change.setApplied(applied);
        return drafts.save(change);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void recognisesImportSessions() {
        assertTrue(SpreadsheetImportDraftSupport.isImportSession("spreadsheet-import-123"));
        assertFalse(SpreadsheetImportDraftSupport.isImportSession("session_169"));
        assertFalse(SpreadsheetImportDraftSupport.isImportSession(null));
    }

    @Test
    void publishingSwitchesTheReceiptAndItsSingleEntryToShared() {
        SpreadsheetImportRecord record = new SpreadsheetImportRecord();
        record.setId("imp-1");
        record.setProjectId("p1");
        record.setUserId("kay");
        record.setDraft(true);
        when(records.findByProjectIdAndUserIdAndDraftTrue("p1", "kay")).thenReturn(List.of(record));

        support.promoteDraftImports("p1", "kay");

        assertFalse(record.isDraft());
        verify(records).save(record);
        verify(history).markDraftEntriesShared("p1", SpreadsheetImportService.ENTITY_IRI_PREFIX + "imp-1");
    }

    @Test
    void nothingToPublishDoesNothing() {
        when(records.findByProjectIdAndUserIdAndDraftTrue("p1", "kay")).thenReturn(List.of());
        support.promoteDraftImports("p1", "kay");
        verify(history, never()).markDraftEntriesShared(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void undoInDraftParksOnlyThatImportsPendingDraftChanges() {
        draft("p1", "kay", "spreadsheet-import-abc", false);
        draft("p1", "kay", "spreadsheet-import-abc", false);
        draft("p1", "kay", "spreadsheet-import-abc", true);
        draft("p1", "kay", "spreadsheet-import-other", false);
        draft("p1", "kay", "session_1", false);
        draft("p1", "lee", "spreadsheet-import-abc", false);
        draft("p2", "kay", "spreadsheet-import-abc", false);

        support.discardDraftChanges("p1", "kay", "spreadsheet-import-abc");

        assertEquals(2, count("draft_change_parked"));
        assertEquals(5, count("draft_change"));
        assertEquals(1, jdbc.queryForObject("select count(*) from draft_change where session_id = 'spreadsheet-import-abc' "
                + "and applied = true", Integer.class));
    }

    @Test
    void redoInDraftPutsTheParkedDraftChangesBackWithTheirIds() {
        DraftChange a = draft("p1", "kay", "spreadsheet-import-abc", false);
        DraftChange b = draft("p1", "kay", "spreadsheet-import-abc", false);
        support.discardDraftChanges("p1", "kay", "spreadsheet-import-abc");
        assertEquals(0, drafts.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "kay").size());

        support.restoreDraftChanges("p1", "kay", "spreadsheet-import-abc");

        assertEquals(0, count("draft_change_parked"));
        List<DraftChange> back = drafts.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "kay");
        assertEquals(2, back.size());
        assertTrue(back.stream().anyMatch(d -> d.getId().equals(a.getId())));
        assertTrue(back.stream().anyMatch(d -> d.getId().equals(b.getId())));
        assertEquals(Map.of("iri", "http://x#A"), back.get(0).getOperationData());
    }

    @Test
    void removingDropsOnlyThePendingChangesOfThatImportSession() {
        draft("p1", "kay", "spreadsheet-import-abc", false);
        draft("p1", "kay", "spreadsheet-import-abc", true);
        draft("p1", "kay", "session_1", false);

        support.removeDraftChanges("p1", "kay", "spreadsheet-import-abc");

        assertEquals(2, count("draft_change"));
    }

    @Test
    void neverTouchesDraftChangesFromOtherSessions() {
        draft("p1", "kay", "session_169", false);
        support.discardDraftChanges("p1", "kay", "session_169");
        support.discardDraftChanges("p1", "kay", null);
        support.removeDraftChanges("p1", "kay", "session_169");
        support.restoreDraftChanges("p1", "kay", "session_169");

        assertEquals(1, count("draft_change"));
        assertEquals(0, count("draft_change_parked"));
    }
}
