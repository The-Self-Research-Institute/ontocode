package self.research.ontology.owlEditor.service;

import static org.mockito.ArgumentMatchers.eq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;
import self.research.ontology.owlEditor.service.UserUndoService.Next;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserUndoServiceTest {

    private static final String P = "proj";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 13, 10, 0);

    private HistoryChangeRepository history;
    private RollbackAuditRepository audits;
    private ChangeRollbackService rollback;
    private UserUndoService service;
    private final Identity me = Identity.of("Me@x.com", "user-1");

    @BeforeEach
    void setUp() {
        history = mock(HistoryChangeRepository.class);
        audits = mock(RollbackAuditRepository.class);
        rollback = mock(ChangeRollbackService.class);
        service = new UserUndoService(history, audits, rollback);
        when(history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(anyString(), anyBoolean(), any(), any()))
                .thenReturn(List.of());
        when(history.findByProjectIdAndEntityIRIOrderByTimestampDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
    }

    private static HistoryChange change(String id, String user, String entity, LocalDateTime at) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setProjectId(P);
        change.setUserId(user);
        change.setUsername(user);
        change.setEntityIRI(entity);
        change.setEntityLabel(entity);
        change.setTimestamp(at);
        change.setOperationType("CLASS_CREATED");
        return change;
    }

    private void ownChanges(HistoryChange... changes) {
        when(history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(anyString(), anyBoolean(), any(), any()))
                .thenReturn(List.of(changes));
    }

    @Test
    void picksTheNewestOwnChangeThatIsNotAnUndo() {
        HistoryChange undo = change("r", "Me@x.com", "e:A", T0.plusMinutes(3));
        undo.setSource(ChangeOrigin.ROLLBACK);
        ownChanges(undo, change("b", "Me@x.com", "e:B", T0.plusMinutes(2)), change("a", "Me@x.com", "e:A", T0));
        Next next = service.nextUndo(P, false, me);
        assertTrue(next.available());
        assertEquals("b", next.changeId());
    }

    @Test
    void skipsChangesAlreadyUndone() {
        HistoryChange reverted = change("b", "Me@x.com", "e:B", T0.plusMinutes(2));
        reverted.setReverted(true);
        ownChanges(reverted, change("a", "Me@x.com", "e:A", T0));
        assertEquals("a", service.nextUndo(P, false, me).changeId());
    }

    @Test
    void reportsNothingToUndoWithoutOwnChanges() {
        Next next = service.nextUndo(P, false, me);
        assertFalse(next.available());
        assertEquals("You have no changes to undo", next.reason());
    }

    @Test
    void refusesAndListsDependentsAddedByOthers() {
        ownChanges(change("a", "Me@x.com", "e:A", T0));
        when(history.findByProjectIdAndEntityIRIOrderByTimestampDesc(eq(P), eq("e:A"), any()))
                .thenReturn(List.of(change("o", "other@x.com", "e:A", T0.plusMinutes(1)),
                        change("a", "Me@x.com", "e:A", T0)));
        Next next = service.nextUndo(P, false, me);
        assertFalse(next.available());
        assertEquals(1, next.dependents().size());
        assertEquals("other@x.com", next.dependents().get(0).username());
    }

    @Test
    void ignoresOthersDependentsInDraftScopeAndOwnLaterChanges() {
        ownChanges(change("a", "Me@x.com", "e:A", T0));
        when(history.findByProjectIdAndEntityIRIOrderByTimestampDesc(eq(P), eq("e:A"), any()))
                .thenReturn(List.of(change("m", "user-1", "e:A", T0.plusMinutes(1)),
                        change("a", "Me@x.com", "e:A", T0)));
        assertTrue(service.nextUndo(P, false, me).available());
        assertTrue(service.nextUndo(P, true, me).available());
    }

    @Test
    void doesNotSkipPastALatestChangeThatCannotBeUndone() {
        HistoryChange prefix = change("p", "Me@x.com", null, T0.plusMinutes(5));
        ownChanges(prefix, change("a", "Me@x.com", "e:A", T0));
        Next next = service.nextUndo(P, false, me);
        assertFalse(next.available());
        assertEquals("p", next.changeId());
    }

    @Test
    void offersRedoForTheNewestStillUndoneAudit() {
        RollbackAudit audit = new RollbackAudit();
        audit.setId("au1");
        audit.setRevertedAt(T0.plusMinutes(10));
        when(audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(audit));
        HistoryChange undone = change("a", "Me@x.com", "e:A", T0);
        undone.setReverted(true);
        undone.setRevertedAuditId("au1");
        when(rollback.entriesForUndo(P, "au1")).thenReturn(List.of(undone));
        Next next = service.nextRedo(P, false, me);
        assertTrue(next.available());
        assertEquals("au1", next.auditId());
    }

    @Test
    void dropsRedoAfterANewChange() {
        RollbackAudit audit = new RollbackAudit();
        audit.setId("au1");
        audit.setRevertedAt(T0.plusMinutes(10));
        when(audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(audit));
        HistoryChange undone = change("a", "Me@x.com", "e:A", T0);
        undone.setReverted(true);
        undone.setRevertedAuditId("au1");
        when(rollback.entriesForUndo(P, "au1")).thenReturn(List.of(undone));
        ownChanges(change("n", "Me@x.com", "e:N", T0.plusMinutes(20)));
        assertFalse(service.nextRedo(P, false, me).available());
    }

    @Test
    void offersNoRedoOnceTheUndoWasRedone() {
        RollbackAudit audit = new RollbackAudit();
        audit.setId("au1");
        audit.setRevertedAt(T0.plusMinutes(10));
        when(audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(audit));
        when(rollback.entriesForUndo(P, "au1")).thenReturn(List.of(change("a", "Me@x.com", "e:A", T0)));
        assertFalse(service.nextRedo(P, false, me).available());
    }

    @Test
    void anUnlabelledChangeIsNamedFromItsOperationAndEntity() {
        HistoryChange change = new HistoryChange();
        change.setOperationType("createClass");
        change.setEntityIRI("http://example.org/onto#DskDiag2");
        change.setDescription("createClass operation");
        assertEquals("Create class DskDiag2", UserUndoService.labelOf(change));
    }

    @Test
    void aLabelledChangeKeepsItsLabelAndInternalIrisFallBackToTheDescription() {
        HistoryChange labelled = new HistoryChange();
        labelled.setEntityLabel("Pizza");
        labelled.setOperationType("createClass");
        labelled.setEntityIRI("http://example.org/onto#Pizza");
        assertEquals("Pizza", UserUndoService.labelOf(labelled));

        HistoryChange merge = new HistoryChange();
        merge.setOperationType("ontologyMerge");
        merge.setEntityIRI("urn:ontocode:merge:abc");
        merge.setDescription("Merged ontology: 3 added");
        assertEquals("Merged ontology: 3 added", UserUndoService.labelOf(merge));
    }
}
