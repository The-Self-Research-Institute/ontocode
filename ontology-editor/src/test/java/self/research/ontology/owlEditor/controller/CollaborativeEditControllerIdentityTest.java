package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.model.collaboration.LockMessage;
import self.research.ontology.owlEditor.model.collaboration.PresenceMessage;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollaborativeEditControllerIdentityTest {

    private CollaborativeEditService service;
    private CollaborativeEditController controller;

    @BeforeEach
    void setUp() {
        service = mock(CollaborativeEditService.class);
        controller = new CollaborativeEditController(mock(SimpMessagingTemplate.class), service,
                mock(WorkspaceOwnershipService.class));
    }

    private static SimpMessageHeaderAccessor session(String userId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId("s1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", "PRO");
        attrs.put("userId", userId);
        accessor.setSessionAttributes(attrs);
        return accessor;
    }

    @Test
    void anEditIsAttributedToTheVerifiedUserNotTheOneInThePayload() {
        EditOperation operation = new EditOperation();
        operation.setUserId("someone-else");

        controller.handleEdit("p1", operation, session("u-1"));

        ArgumentCaptor<EditOperation> sent = ArgumentCaptor.forClass(EditOperation.class);
        verify(service).processEdit(sent.capture());
        assertEquals("u-1", sent.getValue().getUserId());
    }

    @Test
    void presenceIsAttributedToTheVerifiedUser() {
        PresenceMessage message = new PresenceMessage();
        message.setUserId("someone-else");

        controller.handlePresence("p1", message, session("u-1"));

        ArgumentCaptor<PresenceMessage> sent = ArgumentCaptor.forClass(PresenceMessage.class);
        verify(service).processPresence(sent.capture());
        assertEquals("u-1", sent.getValue().getUserId());
    }

    @Test
    void aLockIsTakenForTheVerifiedUser() {
        LockMessage message = new LockMessage();
        message.setType(LockMessage.LockType.LOCK_REQUEST);
        message.setNodeId("n1");
        message.setUserId("someone-else");

        controller.handleLock("p1", message, session("u-1"));

        verify(service).acquireLock(eq("p1"), eq("n1"), eq("u-1"), any(), eq("s1"));
    }

    @Test
    void withoutAVerifiedSessionTheClaimedIdIsKept() {
        EditOperation operation = new EditOperation();
        operation.setUserId("claimed");
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(new HashMap<>(Map.of("plan", "PRO")));

        controller.handleEdit("p1", operation, accessor);

        ArgumentCaptor<EditOperation> sent = ArgumentCaptor.forClass(EditOperation.class);
        verify(service).processEdit(sent.capture());
        assertEquals("claimed", sent.getValue().getUserId());
    }
}
