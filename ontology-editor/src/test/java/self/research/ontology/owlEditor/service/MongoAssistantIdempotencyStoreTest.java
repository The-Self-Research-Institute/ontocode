package self.research.ontology.owlEditor.service;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument.State;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoAssistantIdempotencyStoreTest {

    private MongoTemplate mongoTemplate;
    private MongoAssistantIdempotencyStore store;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        store = new MongoAssistantIdempotencyStore(mongoTemplate);
    }

    @Test
    void duplicateKeyOnInsertMeansTheKeyIsAlreadyTaken() {
        AssistantIdempotencyRecordDocument record = AssistantIdempotencyRecordDocument.builder().id("r").build();
        when(mongoTemplate.insert(record)).thenThrow(new DuplicateKeyException("E11000"));

        assertFalse(store.tryInsert(record));
    }

    @Test
    void successfulInsertClaimsTheKey() {
        AssistantIdempotencyRecordDocument record = AssistantIdempotencyRecordDocument.builder().id("r").build();
        when(mongoTemplate.insert(record)).thenReturn(record);

        assertTrue(store.tryInsert(record));
    }

    @Test
    void takeOverOnlyMatchesExpiredOrStaleInFlightRecords() {
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        Instant staleBefore = Instant.parse("2026-01-01T00:05:00Z");
        AssistantIdempotencyRecordDocument replacement = AssistantIdempotencyRecordDocument.builder().id("r").build();
        when(mongoTemplate.findAndReplace(any(Query.class), eq(replacement), any(FindAndReplaceOptions.class)))
                .thenReturn(replacement);

        assertTrue(store.tryTakeOver("r", staleBefore, now, replacement));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndReplace(captor.capture(), eq(replacement), any(FindAndReplaceOptions.class));
        Document filter = captor.getValue().getQueryObject();
        assertEquals("r", filter.get("_id"));
        @SuppressWarnings("unchecked")
        List<Document> or = (List<Document>) filter.get("$or");
        assertEquals(new Document("expiresAt", new Document("$lt", now)), or.get(0));
        assertEquals(State.IN_FLIGHT, or.get(1).get("state"));
        assertEquals(new Document("$lt", staleBefore), or.get(1).get("updatedAt"));
    }

    @Test
    void completeIsConditionalOnOwnerAndInFlightState() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AssistantIdempotencyRecordDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertTrue(store.complete("r", "owner-1", 200, "application/json", "{}", Instant.now()));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(AssistantIdempotencyRecordDocument.class));
        assertEquals("owner-1", query.getValue().getQueryObject().get("ownerToken"));
        assertEquals(State.IN_FLIGHT, query.getValue().getQueryObject().get("state"));
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertEquals(State.COMPLETED, set.get("state"));
        assertEquals(200, set.get("responseStatus"));
        assertEquals("{}", set.get("responseBody"));
    }

    @Test
    void completeReportsFalseWhenAnotherOwnerTookTheRecord() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AssistantIdempotencyRecordDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertFalse(store.complete("r", "owner-1", 200, "application/json", "{}", Instant.now()));
    }

    @Test
    void abandonOnlyRemovesTheCallersOwnInFlightRecord() {
        store.abandon("r", "owner-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(query.capture(), eq(AssistantIdempotencyRecordDocument.class));
        Document filter = query.getValue().getQueryObject();
        assertEquals("r", filter.get("_id"));
        assertEquals("owner-1", filter.get("ownerToken"));
        assertEquals(State.IN_FLIGHT, filter.get("state"));
    }
}
