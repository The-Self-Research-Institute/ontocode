package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryDraftSessionStamperTest {

    private final DraftSessionRepository sessions = mock(DraftSessionRepository.class);
    private final HistoryDraftSessionStamper stamper = new HistoryDraftSessionStamper(sessions);

    private static HistoryChange row(boolean draft) {
        HistoryChange change = new HistoryChange();
        change.setProjectId("p1");
        change.setUserId("u1");
        change.setDraft(draft);
        return change;
    }

    @Test
    void aDraftRowIsStampedWithTheLiveDraftSession() {
        DraftSession session = new DraftSession("p1", "u1", 1, 1);
        session.setId("s1");
        when(sessions.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.of(session));
        assertEquals("s1", stamper.onBeforeConvert(row(true), "history_changes").getDraftSessionId());
    }

    @Test
    void aPublicRowIsNeverStampedOrLookedUp() {
        assertNull(stamper.onBeforeConvert(row(false), "history_changes").getDraftSessionId());
        verify(sessions, never()).findByProjectIdAndUserId("p1", "u1");
    }

    @Test
    void aDraftRowWithoutASessionStaysUnstamped() {
        when(sessions.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.empty());
        assertNull(stamper.onBeforeConvert(row(true), "history_changes").getDraftSessionId());
    }

    @Test
    void aFailedLookupDoesNotStopTheSave() {
        when(sessions.findByProjectIdAndUserId("p1", "u1")).thenThrow(new RuntimeException("db down"));
        assertNull(stamper.onBeforeConvert(row(true), "history_changes").getDraftSessionId());
    }
}
