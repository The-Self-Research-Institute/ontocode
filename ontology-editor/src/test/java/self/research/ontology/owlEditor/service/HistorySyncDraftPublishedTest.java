package self.research.ontology.owlEditor.service;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistorySyncDraftPublishedTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final HistorySyncService sync = new HistorySyncService(mock(HistoryChangeRepository.class), mongo,
            mock(OntologyHistoryService.class));

    @Test
    void publishingClearsTheDraftFlagOnlyForThatUsersDraftEntriesInThatProject() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(3, 3L, null));

        long modified = sync.markDraftsPublished("p1", "u1");

        assertEquals(3, modified);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateMulti(query.capture(), update.capture(), eq(HistoryChange.class));
        Document criteria = query.getValue().getQueryObject();
        assertEquals("p1", criteria.get("projectId"));
        assertEquals("u1", criteria.get("userId"));
        assertEquals(true, criteria.get("draft"));
        assertEquals(new Document("$ne", true), criteria.get("draftDiscarded"));
        assertEquals(false, ((Document) update.getValue().getUpdateObject().get("$set")).get("draft"));
    }

    @Test
    void publishingAPrsChangeSetsDoesNotDependOnWhichNameTheRowsWereSavedUnderAndSkipsDiscardedOnes() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));

        assertEquals(2, sync.markChangeSetsPublished("p1", java.util.List.of("cs1", "cs2")));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateMulti(query.capture(), any(Update.class), eq(HistoryChange.class));
        Document criteria = query.getValue().getQueryObject();
        assertEquals(false, criteria.containsKey("userId"));
        assertEquals(new Document("$ne", true), criteria.get("draftDiscarded"));
        assertEquals(true, criteria.get("draft"));
    }

    @Test
    void rowsThePublishWroteWithoutAChangeSetAreFoundByAuthorAndTimeAndNeverTheRollbackOrMergeRecords() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(4, 4L, null));
        java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 10, 24, 15, 8, 0);
        java.time.LocalDateTime to = java.time.LocalDateTime.of(2026, 10, 24, 15, 10, 10);

        assertEquals(4, sync.tagUnlinkedRows("p1", "author", from, to, "pr1"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateMulti(query.capture(), update.capture(), eq(HistoryChange.class));
        Document criteria = query.getValue().getQueryObject();
        assertEquals("p1", criteria.get("projectId"));
        assertEquals("author", criteria.get("userId"));
        assertEquals(false, criteria.get("draft"));
        assertEquals(true, criteria.containsKey("$or"));
        assertEquals(null, criteria.get("publishedPrId"));
        assertEquals(new Document("$ne", "ROLLBACK"), criteria.get("source"));
        assertEquals("pr1", ((Document) update.getValue().getUpdateObject().get("$set")).get("publishedPrId"));
    }

    @Test
    void theUndoRowsOfAPrRevertAreFoundByWhatTheyUndid() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(5, 5L, null));

        assertEquals(5, sync.tagRollbackRowsOfPr("p1", java.util.List.of("cs1", "cs2"), "pr1"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateMulti(query.capture(), any(Update.class), eq(HistoryChange.class));
        Document criteria = query.getValue().getQueryObject();
        assertEquals("ROLLBACK", criteria.get("source"));
        assertEquals(true, criteria.containsKey("metadata.revertsChangeSetId"));
        assertEquals(null, criteria.get("publishedPrId"));
    }

    @Test
    void theRowsOfAPrThatWasRevertedAreNotListedAsItsItems() {
        HistoryChangeRepository repo = mock(HistoryChangeRepository.class);
        HistorySyncService local = new HistorySyncService(repo, mongo, mock(OntologyHistoryService.class));
        HistoryChange item = new HistoryChange();
        item.setId("item");
        HistoryChange undo = new HistoryChange();
        undo.setId("undo");
        undo.setSource("ROLLBACK");
        when(repo.findByProjectIdAndPublishedPrId("p1", "pr1")).thenReturn(java.util.List.of(item, undo));

        assertEquals(java.util.List.of("item"), local.publishedRows("p1", "pr1").stream().map(HistoryChange::getId).toList());
    }

    @Test
    void discardingKeepsTheChangeSetsOfAnOpenPullRequest() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        sync.markDraftsDiscarded("p1", "u1", java.util.List.of("cs-open"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateMulti(query.capture(), any(Update.class), eq(HistoryChange.class));
        Document criteria = query.getValue().getQueryObject();
        assertEquals(new Document("$nin", java.util.List.of("cs-open")), criteria.get("changeSetId"));
    }

    @Test
    void thePublishRowWithAChangeSetIsTaggedToo() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));

        sync.tagUnlinkedRows("p1", "author", java.time.LocalDateTime.of(2026, 10, 24, 15, 8),
                java.time.LocalDateTime.of(2026, 10, 24, 15, 10), "pr1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateMulti(query.capture(), any(Update.class), eq(HistoryChange.class));
        assertEquals(true, query.getValue().getQueryObject().containsKey("$or"));
    }

    @Test
    void doesNothingWithoutAProjectAndUser() {
        assertEquals(0, sync.markDraftsPublished(null, "u1"));
        assertEquals(0, sync.markDraftsPublished("p1", null));
        verify(mongo, never()).updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class));
    }

    @Test
    void discardingMarksOnlyThatUsersLiveDraftEntriesInThatProject() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));

        assertEquals(2, sync.markDraftsDiscarded("p1", "u1"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateMulti(query.capture(), update.capture(), eq(HistoryChange.class));
        Document criteria = query.getValue().getQueryObject();
        assertEquals("p1", criteria.get("projectId"));
        assertEquals("u1", criteria.get("userId"));
        assertEquals(true, criteria.get("draft"));
        assertEquals(true, ((Document) update.getValue().getUpdateObject().get("$set")).get("draftDiscarded"));
    }

    @Test
    void discardingWithoutAUserMarksEveryDraftEntryOfTheProject() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        sync.markDraftsDiscarded("p1", null);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateMulti(query.capture(), any(Update.class), eq(HistoryChange.class));
        assertEquals(false, query.getValue().getQueryObject().containsKey("userId"));
    }
}
