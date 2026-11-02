package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.upload.UploadExceptionAdvice;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImportQueueBoundsTest {

    private ImportQueueManager manager(int maxQueued, int perUser) {
        ImportQueueManager m = new ImportQueueManager(mock(SimpMessagingTemplate.class),
                mock(ImportTimeEstimator.class), mock(ProjectMetadataService.class));
        m.configureLimits(maxQueued, perUser);
        return m;
    }

    private void add(ImportQueueManager m, String project, String owner) {
        m.enqueue(project, project + ".owl", owner, Path.of(project + ".owl"));
    }

    @Test
    void queueLengthIsBounded() {
        ImportQueueManager m = manager(3, 100);
        add(m, "p1", "a@x");
        add(m, "p2", "b@x");
        add(m, "p3", "c@x");
        ImportQueueFullException e = assertThrows(ImportQueueFullException.class, () -> add(m, "p4", "d@x"));
        assertEquals("import_queue_full", e.getReason());
        assertEquals(3, m.getQueueStats().getQueuedImports());
    }

    @Test
    void aUserCannotHaveMoreThanTheWaitingCap() {
        ImportQueueManager m = manager(20, 2);
        add(m, "p1", "a@x");
        add(m, "p2", "A@x");
        ImportQueueFullException e = assertThrows(ImportQueueFullException.class, () -> add(m, "p3", "a@x"));
        assertEquals("too_many_waiting_imports", e.getReason());
        add(m, "p4", "someone-else@x");
        assertEquals(3, m.getQueueStats().getQueuedImports());
    }

    @Test
    void duplicateOfAProjectAlreadyQueuedIsStillAcceptedWhenFull() {
        ImportQueueManager m = manager(1, 1);
        add(m, "p1", "a@x");
        add(m, "p1", "a@x");
        assertEquals(1, m.getQueueStats().getQueuedImports());
    }

    @Test
    void checkCapacityThrowsWithoutChangingTheQueue() {
        ImportQueueManager m = manager(1, 5);
        add(m, "p1", "a@x");
        assertThrows(ImportQueueFullException.class, () -> m.checkCapacity("p2", "b@x"));
        assertEquals(1, m.getQueueStats().getQueuedImports());
    }

    @Test
    void queueAgeIsExposed() throws Exception {
        ImportQueueManager m = manager(5, 5);
        assertEquals(0, m.oldestQueuedAgeMs());
        add(m, "p1", "a@x");
        Thread.sleep(20);
        assertTrue(m.oldestQueuedAgeMs() >= 10);
        assertTrue(m.getQueueStats().getOldestQueuedAgeMs() >= 10);
    }

    @Test
    void busyResponseBodyHasTheAgreedShape() {
        ImportQueueFullException e = new ImportQueueFullException("import_queue_full", 30, "Try later");
        ResponseEntity<Map<String, Object>> r = UploadExceptionAdvice.busy(e);
        assertEquals(429, r.getStatusCode().value());
        Map<String, Object> body = r.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("busy"));
        assertEquals("import_queue_full", body.get("reason"));
        assertEquals(30, body.get("retryAfterSeconds"));
        assertEquals("Try later", body.get("message"));
        assertSame(e, assertThrows(ImportQueueFullException.class, () -> {
            throw e;
        }));
    }
}
