package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyCrudControllerActorTest {

    private static OntologyCrudController.MutationRequest request(String userId) {
        return new OntologyCrudController.MutationRequest(
                List.of(new OntologyMutationService.MutationOp("createClass", "http://x/A", "A", null, null, null, null,
                        null, null, null, null, null, null, null, null)), userId, "Graph View Plugin", "s1");
    }

    private static MockHttpServletRequest signedIn(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"mongo-1\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private ArgumentCaptor<String> publicEditIsRecordedFor(String sentUserId, MockHttpServletRequest http) {
        ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any())).thenReturn("cs");
        OntologyCrudController controller = new OntologyCrudController(mock(OntologyMutationService.class),
                mock(DraftTrackingService.class), mock(CollaborativeEditService.class), mock(ProjectMetadataService.class),
                new ProjectWriteLockRegistry(), recorder);
        controller.mutate("proj", request(sentUserId), false, http);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(recorder).recordGrouped(eq("proj"), eq(false), user.capture(), anyString(), any(), any());
        return user;
    }

    @Test
    void aPublicEditIsRecordedUnderTheSignedInUserNotThePlaceholderTheClientSent() {
        assertEquals("real@x.org", publicEditIsRecordedFor("graph-view-plugin", signedIn("real@x.org")).getValue());
    }

    @Test
    void aDraftEditWithoutAUserIdGoesToTheSignedInUsersDraft() {
        ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any())).thenReturn("cs");
        OntologyCrudController controller = new OntologyCrudController(mock(OntologyMutationService.class),
                mock(DraftTrackingService.class), mock(CollaborativeEditService.class), mock(ProjectMetadataService.class),
                new ProjectWriteLockRegistry(), recorder);
        controller.mutate("proj", request(null), true, signedIn("real@x.org"));
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(recorder).recordGrouped(eq("proj"), eq(true), user.capture(), anyString(), any(), any());
        assertEquals("mongo-1", user.getValue());
    }

    @Test
    void withoutASignedInUserTheClientsIdIsKept() {
        assertEquals("u1", publicEditIsRecordedFor("u1", new MockHttpServletRequest()).getValue());
    }

    private String draftEditName(String sentName, MockHttpServletRequest http) {
        ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any())).thenReturn("cs");
        OntologyCrudController controller = new OntologyCrudController(mock(OntologyMutationService.class),
                mock(DraftTrackingService.class), mock(CollaborativeEditService.class), mock(ProjectMetadataService.class),
                new ProjectWriteLockRegistry(), recorder);
        controller.mutate("proj", new OntologyCrudController.MutationRequest(request(null).ops(), null, sentName, "s1"), true, http);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(recorder).recordGrouped(eq("proj"), eq(true), anyString(), name.capture(), any(), any());
        return name.getValue();
    }

    @Test
    void aDraftEditIsNamedAfterTheSignedInUserWhenTheClientSentAPlaceholder() {
        assertEquals("real@x.org", draftEditName("Anonymous", signedIn("real@x.org")));
        assertEquals("real@x.org", draftEditName(null, signedIn("real@x.org")));
        assertEquals("real@x.org", draftEditName("", signedIn("real@x.org")));
    }

    @Test
    void aDraftEditKeepsARealNameTheClientSent() {
        assertEquals("Sam Lee", draftEditName("Sam Lee", signedIn("real@x.org")));
    }

    @Test
    void aDraftEditWithNobodySignedInIsStillAnonymous() {
        assertEquals("Anonymous", draftEditName(null, new MockHttpServletRequest()));
    }
}
