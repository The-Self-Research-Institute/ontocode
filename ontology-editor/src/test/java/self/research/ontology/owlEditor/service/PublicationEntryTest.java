package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationEntryTest {

    private static HistoryChange row(String id, String op, String user) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setOperationType(op);
        change.setUserId(user);
        change.setUsername(user);
        change.setEntityLabel(op);
        return change;
    }

    @Test
    void aMergedPrEntryIsNeverUndoableAndPointsToRevert() {
        UndoAbility.Result result = UndoAbility.of(row("p", "prPublished", "owner"));
        assertFalse(result.undoable());
        assertEquals("This records a pull request being merged. Revert the pull request instead", result.reason());
    }

    @Test
    void aMergedPrEntryDoesNotBlockAnUndo() {
        HistoryChangeRepository history = mock(HistoryChangeRepository.class);
        UndoBlockers blockers = new UndoBlockers(history, mock(ProjectImportService.class));
        HistoryChange mine = row("m", "createClass", "owner");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                row("p", "prPublished", "owner"), row("r", "prReverted", "owner"), mine));
        assertEquals(List.of(), blockers.laterChanges("proj", mine, null));
    }

    @Test
    void theMergedPrEntryOfTheSamePersonIsTheNextUndoTargetButARevertEntryIsNot() {
        HistoryChangeRepository history = mock(HistoryChangeRepository.class);
        UserUndoService service = new UserUndoService(history, mock(self.research.ontology.owlEditor.repository.RollbackAuditRepository.class),
                mock(ChangeRollbackService.class));
        HistoryChange edit = row("e", "createClass", "owner");
        edit.setEntityIRI("urn:x#A");
        HistoryChange merged = row("p", "prPublished", "owner");
        merged.setEntityIRI("urn:ontocode:pr:pr1");
        when(history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(List.of(row("r", "prReverted", "owner"), merged, edit));
        when(history.findByProjectIdAndDraftOrderByTimestampDesc(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(List.of(row("r", "prReverted", "owner"), merged, edit));
        UserUndoService.Next next = service.nextUndo("proj", false, UserUndoService.Identity.of("owner"));
        assertEquals("p", next.changeId());
        assertTrue(next.available());
    }
}
