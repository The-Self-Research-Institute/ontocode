package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftPullStatusControllerTest {

    private final DraftSessionRepository sessions = mock(DraftSessionRepository.class);
    private final MainGraphRevisionService revisions = mock(MainGraphRevisionService.class);
    private DraftPullStatusController controller;

    @BeforeEach
    void setUp() {
        controller = new DraftPullStatusController(sessions, revisions);
    }

    private static DraftSession sessionAt(long baselineRevision) {
        return new DraftSession("p1", "u1", baselineRevision, 10);
    }

    @Test
    void aPullIsPendingWhenPublicMovedPastTheDraftsBaseline() {
        when(sessions.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.of(sessionAt(3)));
        when(revisions.getRevision("p1")).thenReturn(5L);

        Map<String, Object> body = controller.pullStatus("p1", "u1").getBody();

        assertEquals(true, body.get("pending"));
        assertEquals(5L, body.get("publicRevision"));
        assertEquals(3L, body.get("baselineRevision"));
    }

    @Test
    void nothingIsPendingWhenTheDraftIsUpToDate() {
        when(sessions.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.of(sessionAt(5)));
        when(revisions.getRevision("p1")).thenReturn(5L);

        assertEquals(false, controller.pullStatus("p1", "u1").getBody().get("pending"));
    }

    @Test
    void withoutADraftNothingIsPending() {
        when(sessions.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.empty());

        Map<String, Object> body = controller.pullStatus("p1", "u1").getBody();

        assertEquals(false, body.get("hasDraft"));
        assertEquals(false, body.get("pending"));
    }
}
