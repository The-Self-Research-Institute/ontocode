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
        assertEquals(false, ((Document) update.getValue().getUpdateObject().get("$set")).get("draft"));
    }

    @Test
    void doesNothingWithoutAProjectAndUser() {
        assertEquals(0, sync.markDraftsPublished(null, "u1"));
        assertEquals(0, sync.markDraftsPublished("p1", null));
        verify(mongo, never()).updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class));
    }
}
