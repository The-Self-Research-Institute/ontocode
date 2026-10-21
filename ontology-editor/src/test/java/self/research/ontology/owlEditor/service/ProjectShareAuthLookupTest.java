package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.repository.ProjectShareRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProjectShareAuthLookupTest {

    private MockRestServiceServer auth;
    private ProjectShareService service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        auth = MockRestServiceServer.createServer(restTemplate);
        ProjectShareRepository repository = mock(ProjectShareRepository.class);
        when(repository.findByProjectId("p1")).thenReturn(Optional.empty());
        when(repository.save(any(ProjectShare.class))).thenAnswer(call -> call.getArgument(0));
        service = new ProjectShareService(repository, restTemplate, mock(EmailNotificationService.class),
                mock(ProjectMetadataService.class), mock(SimpMessagingTemplate.class));
        ReflectionTestUtils.setField(service, "authServiceUrl", "http://auth:8086");
        ReflectionTestUtils.setField(service, "internalToken", "s3cret");
    }

    @Test
    void theOwnersEmailIsLookedUpOnTheInternalRouteWithTheInternalToken() {
        auth.expect(requestTo("http://auth:8086/api/internal/users/email?username=alice"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Ontocode-Internal-Token", "s3cret"))
                .andRespond(withSuccess("{\"username\":\"alice\",\"email\":\"alice@x.org\"}", MediaType.APPLICATION_JSON));

        ProjectShare share = service.createShare("p1", "alice", "view");

        assertEquals("alice@x.org", share.getOwnerEmail());
        auth.verify();
    }

    @Test
    void aUsernameCannotInjectExtraQueryParameters() {
        auth.expect(requestTo("http://auth:8086/api/internal/users/email?username=a%26admin%3Dtrue"))
                .andRespond(withSuccess("{\"email\":\"a@x.org\"}", MediaType.APPLICATION_JSON));

        service.createShare("p1", "a&admin=true", "view");

        auth.verify();
    }
}
