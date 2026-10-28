package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.model.collaboration.ShareNotification;
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
    private MongoOperations mongo;
    private ProjectShareService service;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        mongo = mock(MongoOperations.class);
        ProjectShareRepository repository = mock(ProjectShareRepository.class);
        when(repository.findByProjectId("p1")).thenReturn(Optional.of(new ProjectShare("p1", "ann@x.org", "view")));
        when(repository.save(any(ProjectShare.class))).thenAnswer(call -> call.getArgument(0));
        service = new ProjectShareService(repository, new RestTemplate(), mock(EmailNotificationService.class),
                mock(ProjectMetadataService.class), messaging);
        ReflectionTestUtils.setField(service, "mongo", mongo);
    }

    @Test
    void theNotificationGoesToTheRecipientsOwnQueueAndNotToAnyoneElse() {
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("users")))
                .thenReturn(new Document("_id", "u-bob").append("email", "bob@x.org"));

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
