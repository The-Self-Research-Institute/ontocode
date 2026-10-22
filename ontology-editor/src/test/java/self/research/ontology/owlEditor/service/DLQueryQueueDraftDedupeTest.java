package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.model.DLQueryJob;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

class DLQueryQueueDraftDedupeTest {

    private DLQueryQueueManager manager;

    @BeforeEach
    void setUp() {
        manager = new DLQueryQueueManager(mock(SimpMessagingTemplate.class), mock(SparqlDatasetService.class),
            new DLQueryConcurrencyPolicy(mock(ImportQueueManager.class)));
    }

    private DLQueryJob enqueue(String draftUserId) {
        return manager.enqueue("p1", "Location", List.of("instances"), null, draftUserId);
    }

    @Test
    void theSameUserAskingTwiceReusesTheJob() {
        assertEquals(enqueue("user-a").getJobId(), enqueue("user-a").getJobId());
    }

    @Test
    void twoUsersNeverShareADraftJob() {
        DLQueryJob a = enqueue("user-a");
        DLQueryJob b = enqueue("user-b");

        assertNotEquals(a.getJobId(), b.getJobId());
        assertEquals("user-a", a.getDraftUserId());
        assertEquals("user-b", b.getDraftUserId());
    }

    @Test
    void publicAndDraftQueriesNeverShareAJob() {
        DLQueryJob live = enqueue(null);
        DLQueryJob draft = enqueue("user-a");

        assertNotEquals(live.getJobId(), draft.getJobId());
        assertEquals(live.getJobId(), enqueue(null).getJobId());
    }
}
