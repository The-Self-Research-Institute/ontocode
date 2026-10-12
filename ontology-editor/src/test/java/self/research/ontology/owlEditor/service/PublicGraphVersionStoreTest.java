package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.document.PublicGraphVersionDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicGraphVersionStoreTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final PublicGraphVersionStore store = new PublicGraphVersionStore(mongoTemplate);

    @Test
    void incrementIsASingleAtomicUpsertThatReturnsTheNewValue() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(PublicGraphVersionDocument.class)))
                .thenReturn(PublicGraphVersionDocument.builder().id("proj-1").version(8L).build());

        long value = store.increment("proj-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(query.capture(), update.capture(), options.capture(),
                eq(PublicGraphVersionDocument.class));
        assertEquals(8L, value);
        assertEquals("proj-1", query.getValue().getQueryObject().get("_id"));
        Document inc = (Document) update.getValue().getUpdateObject().get("$inc");
        assertEquals(1L, inc.get("version"));
        assertTrue(options.getValue().isUpsert());
        assertTrue(options.getValue().isReturnNew());
    }

    @Test
    void incrementWithoutAResultFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> store.increment("proj-1"));
    }

    @Test
    void readReturnsPersistedValueOrZero() {
        when(mongoTemplate.findOne(any(Query.class), eq(PublicGraphVersionDocument.class)))
                .thenReturn(PublicGraphVersionDocument.builder().id("proj-1").version(12L).build())
                .thenReturn(null);

        assertEquals(12L, store.read("proj-1"));
        assertEquals(0L, store.read("proj-2"));
    }
}
