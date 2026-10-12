package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeSetBroadcasterTest {

    private static HistoryChange change(String op, String iri) {
        return new HistoryChange.Builder("p", "e-" + op + iri, "u@x.com", "u@x.com").operationType(op).entityIRI(iri).build();
    }

    @Test
    void describesWhatAChangeSetDidInPlainWords() {
        List<HistoryChange> aiClasses = List.of(change("createClass", "A"), change("createClass", "B"),
                change("codeViewStructuralEdit", null));
        assertEquals("Added 2 classes with Ask AI", ChangeSetBroadcaster.describe(aiClasses, ChangeOrigin.AI));
        assertEquals("Added 1 class and changed 1 entity in Code View", ChangeSetBroadcaster.describe(
                List.of(change("createClass", "A"), change("addStatement", "B")), ChangeOrigin.MANUAL));
        assertEquals("Deleted 1 property in Code View",
                ChangeSetBroadcaster.describe(List.of(change("deleteObjectProperty", "P")), ChangeOrigin.MANUAL));
        assertEquals("Applied an Ask AI edit",
                ChangeSetBroadcaster.describe(List.of(change("codeViewStructuralEdit", null)), ChangeOrigin.AI));
    }

    @Test
    void broadcastsTheChangeSetWithWhoMadeIt() {
        HistorySyncService sync = mock(HistorySyncService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ChangeOrigin origin = ChangeOrigin.ai("group-1", "claude", "m", "s", "summary");
        when(sync.getChangeSet("p", "group-1")).thenReturn(List.of(change("createClass", "A")));

        new ChangeSetBroadcaster(sync, messaging).changeSetApplied("p", "u@x.com", "u@x.com", origin);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/ontology/p"), payload.capture());
        Map<?, ?> event = (Map<?, ?>) payload.getValue();
        assertEquals("CHANGE_SET_APPLIED", event.get("type"));
        assertEquals("Added 1 class with Ask AI", event.get("description"));
        assertEquals("u@x.com", event.get("userEmail"));
        assertEquals("group-1", event.get("changeSetId"));
    }

    @Test
    void staysQuietWhenNothingWasRecorded() {
        HistorySyncService sync = mock(HistorySyncService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(sync.getChangeSet(anyString(), anyString())).thenReturn(List.of());

        new ChangeSetBroadcaster(sync, messaging).changeSetApplied("p", "user-1", "u", ChangeOrigin.manual());

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }
}
