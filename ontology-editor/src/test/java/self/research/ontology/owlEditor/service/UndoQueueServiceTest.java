package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.UndoQueueService.Queue;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UndoQueueServiceTest {

    private static final String P = "proj";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 13, 10, 0);

    private HistoryChangeRepository history;
    private UndoQueueService service;
    private final Identity me = Identity.of("Me@x.com", "mongo-1");

    @BeforeEach
    void setUp() {
        history = mock(HistoryChangeRepository.class);
        UserUndoService undo = new UserUndoService(history, mock(RollbackAuditRepository.class),
                mock(ChangeRollbackService.class));
        service = new UndoQueueService(history, undo);
        when(history.findByProjectIdAndEntityIRIOrderByTimestampDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
    }

    private static HistoryChange change(String id, String user, String entity, int minute) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setProjectId(P);
        change.setUserId(user);
        change.setUsername(user);
        change.setEntityIRI(entity);
        change.setEntityLabel(entity);
        change.setTimestamp(T0.plusMinutes(minute));
        change.setOperationType("CLASS_CREATED");
        return change;
    }

    private void own(HistoryChange... changes) {
        when(history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(anyString(), anyBoolean(), any(), any()))
                .thenReturn(List.of(changes));
        when(history.findByProjectIdAndDraftOrderByTimestampDesc(anyString(), anyBoolean(), any()))
                .thenReturn(List.of(changes));
    }

    @Test
    void thePublicQueueIncludesOtherPeoplesChangesNewestFirst() {
        own(change("s", "Sam@x.com", "e:S", 6), change("a", "Me@x.com", "e:A", 1));

        Queue queue = service.queue(P, false, me, 5);

        assertEquals("s", queue.steps().get(0).changeId());
        assertEquals("a", queue.steps().get(1).changeId());
    }

    @Test
    void aDiscardedDraftEntryIsNotOfferedAsAStep() {
        HistoryChange discarded = change("d", "Me@x.com", "e:D", 5);
        discarded.setDraftDiscarded(true);
        own(discarded, change("a", "Me@x.com", "e:A", 1));

        Queue queue = service.queue(P, true, me, 5);

        assertEquals(1, queue.steps().size());
        assertEquals("a", queue.steps().get(0).changeId());
    }

    @Test
    void returnsNewestSetsFirstAndGroupsEntriesOfOneSet() {
        HistoryChange c = change("c", "Me@x.com", "e:C", 5);
        HistoryChange b2 = change("b2", "Me@x.com", "e:B2", 4);
        HistoryChange b1 = change("b1", "Me@x.com", "e:B1", 3);
        b2.setChangeSetId("set-b");
        b1.setChangeSetId("set-b");
        own(c, b2, b1, change("a", "Me@x.com", "e:A", 1));
        Queue queue = service.queue(P, false, me, 5);
        assertEquals(3, queue.steps().size());
        assertEquals("c", queue.steps().get(0).changeId());
        assertEquals("set-b", queue.steps().get(1).changeSetId());
        assertEquals(2, queue.steps().get(1).changeCount());
        assertEquals("a", queue.steps().get(2).changeId());
        assertNull(queue.blocked());
    }

    @Test
    void skipsRevertedAndRollbackEntries() {
        HistoryChange reverted = change("r", "Me@x.com", "e:R", 4);
        reverted.setReverted(true);
        HistoryChange rollback = change("rb", "Me@x.com", "e:A", 3);
        rollback.setSource(ChangeOrigin.ROLLBACK);
        own(reverted, rollback, change("a", "Me@x.com", "e:A", 1));
        Queue queue = service.queue(P, false, me, 5);
        assertEquals(1, queue.steps().size());
        assertEquals("a", queue.steps().get(0).changeId());
    }

    @Test
    void blockedEntryStopsTheWalk() {
        own(change("c", "Me@x.com", "e:C", 5), change("b", "Me@x.com", "e:B", 3), change("a", "Me@x.com", "e:A", 1));
        when(history.findByProjectIdAndEntityIRIOrderByTimestampDesc(anyString(), anyString(), any()))
                .thenAnswer(inv -> "e:B".equals(inv.getArgument(1))
                        ? List.of(change("o", "other@x.com", "e:B", 4)) : List.of());
        Queue queue = service.queue(P, false, me, 5);
        assertEquals(1, queue.steps().size());
        assertEquals("c", queue.steps().get(0).changeId());
        assertNotNull(queue.blocked());
        assertEquals("b", queue.blocked().changeId());
        assertEquals(1, queue.blocked().dependents().size());
    }

    @Test
    void capsCountAtTwentyFive() {
        List<HistoryChange> many = new ArrayList<>();
        for (int i = 40; i > 0; i--) {
            many.add(change("c" + i, "Me@x.com", "e:" + i, i));
        }
        own(many.toArray(new HistoryChange[0]));
        assertEquals(25, service.queue(P, false, me, 100).steps().size());
        assertEquals(1, service.queue(P, false, me, 0).steps().size());
    }

    @Test
    void findsHistoryStoredUnderTheMongoIdWhenSubjectIsTheEmail() {
        own(change("m", "mongo-1", "e:M", 2));
        Queue queue = service.queue(P, false, me, 1);
        assertEquals("m", queue.steps().get(0).changeId());
    }
}
