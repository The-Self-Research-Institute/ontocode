package self.research.ontology.owlEditor.service;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRollbackActionsTest {

    private static final String OLD = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"p\",\"name\":\"old\"}";
    private static final String NEW = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"p\",\"name\":\"new\"}";

    private MongoCollection<Document> collection;
    private FindIterable<Document> found;
    private DocumentRollbackActions actions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MongoTemplate template = mock(MongoTemplate.class);
        collection = mock(MongoCollection.class);
        found = mock(FindIterable.class);
        when(template.getCollection("fuzzy_rules")).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(found);
        actions = new DocumentRollbackActions(template, "http://localhost:1");
    }

    private static RollbackAction restore(String target, String expected) {
        return RollbackAction.restoreDocument("fuzzyRule", "6acf64ae94e5320b04bb880a", target, expected);
    }

    @Test
    void theCurrentDocumentMustStillEqualTheExpectedOne() {
        when(found.first()).thenReturn(Document.parse(NEW));

        assertTrue(actions.holds("p", restore(OLD, NEW), false, null));
        assertFalse(actions.holds("p", restore(NEW, OLD), false, null));
    }

    @Test
    void aMissingDocumentOnlyMatchesAnExpectedAbsence() {
        when(found.first()).thenReturn(null);

        assertTrue(actions.holds("p", restore(OLD, null), false, null));
        assertFalse(actions.holds("p", restore(OLD, NEW), false, null));
    }

    @Test
    void restoringReplacesTheDocumentInPlaceKeepingItsId() {
        actions.run("p", restore(OLD, NEW), false, null);

        verify(collection).replaceOne(any(Bson.class), org.mockito.ArgumentMatchers.eq(Document.parse(OLD)), any(ReplaceOptions.class));
        verify(collection, never()).deleteOne(any(Bson.class));
    }

    @Test
    void aDocumentOfAnotherProjectIsNeverWritten() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> actions.run("other", restore(OLD, NEW), false, null));
        verify(collection, never()).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test
    void executionCountersDoNotCountAsAChange() {
        String ran = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"p\",\"name\":\"new\",\"executionCount\":3}";
        when(found.first()).thenReturn(Document.parse(ran));
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.getCollection("swrl_rules")).thenReturn(collection);
        DocumentRollbackActions swrl = new DocumentRollbackActions(template, "http://localhost:1");

        assertTrue(swrl.holds("p", RollbackAction.restoreDocument("swrlRule", "6acf64ae94e5320b04bb880a", OLD, NEW), false, null));
        when(found.first()).thenReturn(Document.parse(ran));
        assertFalse(actions.holds("p", restore(OLD, NEW), false, null));
    }

    @Test
    void restoringAnAbsentStateDeletesTheDocument() {
        actions.run("p", restore(null, NEW), false, null);

        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void anUnknownStoreNeverHolds() {
        assertFalse(actions.holds("p", RollbackAction.restoreDocument("nope", "1", OLD, NEW), false, null));
    }

    @Test
    void theCompositeSendsDocumentActionsToTheDocumentRunnerAndTheRestToThePrefixRunner() {
        PrefixRollbackActions prefix = mock(PrefixRollbackActions.class);
        DocumentRollbackActions document = mock(DocumentRollbackActions.class);
        CompositeRollbackActions composite = new CompositeRollbackActions(prefix, document, mock(StoredFileRollbackActions.class), mock(MergeCheckpointRollbackActions.class));

        composite.run("p", restore(OLD, NEW), false, null);
        composite.run("p", RollbackAction.deletePrefix("ex", "http://x/"), false, null);

        verify(document).run("p", restore(OLD, NEW), false, null);
        verify(prefix).run("p", RollbackAction.deletePrefix("ex", "http://x/"), false, null);
    }
}
