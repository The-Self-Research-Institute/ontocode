package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.model.collaboration.ShareNotification;
import self.research.ontology.owlEditor.repository.ProjectAccessRepository;
import self.research.ontology.owlEditor.repository.ProjectShareRepository;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectShareNotificationTest {

    private SimpMessagingTemplate messaging;
    private ProjectAccessRepository accounts;
    private ProjectShareService service;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        accounts = mock(ProjectAccessRepository.class);
        ProjectShareRepository repository = mock(ProjectShareRepository.class);
        when(repository.addSharedEmail(eq("p1"), anyString(), any(), any()))
                .thenReturn(Optional.of(new ProjectShare("p1", "ann@x.org", "view")));
        service = new ProjectShareService(repository, new RestTemplate(), mock(EmailNotificationService.class),
                mock(ProjectMetadataService.class), messaging, accounts);
    }

    @Test
    void theNotificationGoesToTheRecipientsOwnQueueAndNotToAnyoneElse() {
        when(accounts.findUserIdByEmail("Bob@x.org")).thenReturn(Optional.of("u-bob"));

        service.addEmailAccess("p1", "Bob@x.org", "edit");

        verify(messaging).convertAndSendToUser(eq("u-bob"), eq("/queue/shares"), any(ShareNotification.class));
        verify(messaging).convertAndSendToUser(eq("bob@x.org"), eq("/queue/shares"), any(ShareNotification.class));
        verify(messaging, never()).convertAndSendToUser(eq("u-ann"), anyString(), any());
        verify(messaging, never()).convertAndSendToUser(eq("ann@x.org"), anyString(), any());
        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void aRecipientWithoutAnAccountIsReachedByTheirAddressOnly() {
        service.addEmailAccess("p1", "carol@x.org", "view");

        verify(messaging).convertAndSendToUser(eq("carol@x.org"), eq("/queue/shares"), any(ShareNotification.class));
        verify(messaging, never()).convertAndSendToUser(eq("u-bob"), anyString(), any());
    }
}
