package self.research.ontology.owlEditor.service.spreadsheet;

import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.repository.SpreadsheetImportRecordRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpreadsheetImportDraftSupportTest {

    private final SpreadsheetImportRecordRepository records = mock(SpreadsheetImportRecordRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final SpreadsheetImportDraftSupport support = new SpreadsheetImportDraftSupport(records, mongo);

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
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateMulti(query.capture(), update.capture(), eq(HistoryChange.class));
        assertEquals(SpreadsheetImportService.ENTITY_IRI_PREFIX + "imp-1", query.getValue().getQueryObject().get("entityIRI"));
        assertEquals(Boolean.TRUE, query.getValue().getQueryObject().get("draft"));
        assertTrue(update.getValue().getUpdateObject().toJson().contains("\"draft\": false"));
    }

    @Test
    void nothingToPublishDoesNothing() {
        when(records.findByProjectIdAndUserIdAndDraftTrue("p1", "kay")).thenReturn(List.of());
        support.promoteDraftImports("p1", "kay");
        verify(mongo, never()).updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class));
    }

    @Test
    void undoInDraftParksOnlyThatImportsPendingDraftChanges() {
        List<DraftChange> pending = List.of(new DraftChange(), new DraftChange());
        when(mongo.find(any(Query.class), eq(DraftChange.class))).thenReturn(pending);
        when(mongo.remove(any(Query.class), eq(DraftChange.class))).thenReturn(DeleteResult.acknowledged(2));

        support.discardDraftChanges("p1", "kay", "spreadsheet-import-abc");

        verify(mongo).insert(pending, SpreadsheetImportDraftSupport.PARKED_COLLECTION);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(query.capture(), eq(DraftChange.class));
        var q = query.getValue().getQueryObject();
        assertEquals("p1", q.get("projectId"));
        assertEquals("kay", q.get("userId"));
        assertEquals("spreadsheet-import-abc", q.get("sessionId"));
        assertEquals(Boolean.FALSE, q.get("applied"));
    }

    @Test
    void redoInDraftPutsTheParkedDraftChangesBack() {
        List<DraftChange> parked = List.of(new DraftChange(), new DraftChange());
        when(mongo.find(any(Query.class), eq(DraftChange.class), eq(SpreadsheetImportDraftSupport.PARKED_COLLECTION)))
            .thenReturn(parked);

        support.restoreDraftChanges("p1", "kay", "spreadsheet-import-abc");

        verify(mongo).insert(parked, DraftChange.class);
        verify(mongo).remove(any(Query.class), eq(DraftChange.class), eq(SpreadsheetImportDraftSupport.PARKED_COLLECTION));
    }

    @Test
    void neverRemovesDraftChangesFromOtherSessions() {
        support.discardDraftChanges("p1", "kay", "session_169");
        support.discardDraftChanges("p1", "kay", null);
        verify(mongo, never()).remove(any(Query.class), eq(DraftChange.class));
    }
}
