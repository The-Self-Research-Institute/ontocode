package self.research.ontology.owlEditor.migration;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectUserIdBackfillTest {

    private MongoOperations mongo;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoOperations.class);
        when(mongo.find(any(Query.class), eq(Document.class), eq("users"))).thenReturn(List.of(
                new Document("_id", "u-alice").append("email", "Alice@X.org"),
                new Document("_id", "u-bob").append("email", "bob@x.org")));
    }

    private void projects(Document... docs) {
        when(mongo.find(any(Query.class), eq(Document.class), eq("projects"))).thenReturn(List.of(docs));
    }

    private Document lastSet() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(1)).updateFirst(any(Query.class), update.capture(), eq("projects"));
        return (Document) update.getValue().getUpdateObject().get("$set");
    }

    @Test
    void theOwnerAndMembersGetTheirUserIdsIgnoringEmailCase() {
        projects(new Document("_id", "p1").append("ownerEmail", "alice@x.org")
                .append("members", List.of(new Document("email", "BOB@x.org"), new Document("email", "carol@x.org"))));

        int updated = ProjectUserIdBackfill.run(mongo);

        assertEquals(1, updated);
        Document set = lastSet();
        assertEquals("u-alice", set.get("ownerId"));
        List<?> members = (List<?>) set.get("members");
        assertEquals("u-bob", ((Document) members.get(0)).get("userId"));
        assertFalse(((Document) members.get(1)).containsKey("userId"));
    }

    @Test
    void aProjectThatAlreadyHasIdsIsLeftAlone() {
        projects(new Document("_id", "p2").append("ownerEmail", "alice@x.org").append("ownerId", "u-alice")
                .append("members", List.of(new Document("email", "bob@x.org").append("userId", "u-bob"))));

        assertEquals(0, ProjectUserIdBackfill.run(mongo));

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq("projects"));
    }

    @Test
    void anOwnerWithNoMatchingUserIsLeftWithoutAnId() {
        projects(new Document("_id", "p3").append("ownerEmail", "ghost@x.org"));

        assertEquals(0, ProjectUserIdBackfill.run(mongo));

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq("projects"));
    }

    @Test
    void runningItTwiceChangesNothingTheSecondTime() {
        Document first = new Document("_id", "p1").append("ownerEmail", "alice@x.org");
        projects(first);
        assertEquals(1, ProjectUserIdBackfill.run(mongo));

        first.put("ownerId", "u-alice");
        projects(first);

        assertEquals(0, ProjectUserIdBackfill.run(mongo));
    }

    @Test
    void withNoUsersAtAllNothingIsTouched() {
        when(mongo.find(any(Query.class), eq(Document.class), eq("users"))).thenReturn(List.of());
        projects(new Document("_id", "p1").append("ownerEmail", "alice@x.org"));

        assertEquals(0, ProjectUserIdBackfill.run(mongo));

        assertTrue(true);
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq("projects"));
    }
}
