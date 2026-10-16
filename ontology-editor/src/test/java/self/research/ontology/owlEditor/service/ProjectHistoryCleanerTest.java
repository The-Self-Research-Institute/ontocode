package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.repository.UndoIntentRepository;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProjectHistoryCleanerTest {

    @Test
    void purgeRemovesHistoryAuditsAndIntentsOfOneProject() {
        HistoryChangeRepository history = mock(HistoryChangeRepository.class);
        RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
        UndoIntentRepository intents = mock(UndoIntentRepository.class);

        new ProjectHistoryCleaner(history, audits, intents).purge("p1");

        verify(history).deleteByProjectId("p1");
        verify(audits).deleteByProjectId("p1");
        verify(intents).deleteByProjectId("p1");
    }

    @Test
    void aFailingStoreDoesNotBreakTheProjectDelete() {
        HistoryChangeRepository history = mock(HistoryChangeRepository.class);
        doThrow(new IllegalStateException("down")).when(history).deleteByProjectId("p1");

        new ProjectHistoryCleaner(history, mock(RollbackAuditRepository.class), mock(UndoIntentRepository.class)).purge("p1");
    }
}
