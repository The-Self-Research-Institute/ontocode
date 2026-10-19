package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EndpointActorTest {

    private static MockHttpServletRequest signedIn(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"mongo-1\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void aSparqlUpdateIsRecordedUnderTheSignedInUserNotAnonymous() {
        OntologyMutationService mutations = mock(OntologyMutationService.class);
        SparqlQueryController controller = new SparqlQueryController(mock(SparqlDatasetService.class),
                mock(SimpMessagingTemplate.class), mock(SparqlQueryRepository.class), mutations);

        controller.update("proj", new SparqlQueryController.SparqlRequest("INSERT DATA { <http://x/a> <http://x/p> 1 }"),
                "anonymous", "Anonymous", false, signedIn("real@x.org"));

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(mutations).applyRawUpdateWithHistory(eq("proj"), anyString(), eq(false), user.capture(), anyString());
        assertEquals("real@x.org", user.getValue());
    }

    @Test
    void aDraftKeepsItsOwnerAndOnlyFallsBackWhenNoneWasSent() {
        MockHttpServletRequest request = signedIn("real@x.org");
        assertEquals("owner-id", RequestActor.forScope(request, "owner-id", true));
        assertEquals("mongo-1", RequestActor.forScope(request, "anonymous", true));
        assertEquals("real@x.org", RequestActor.forScope(request, "owner-id", false));
        assertEquals("u1", RequestActor.forScope(new MockHttpServletRequest(), "u1", false));
        assertEquals("anonymous", RequestActor.forScope(null, "anonymous", true));
    }
}
