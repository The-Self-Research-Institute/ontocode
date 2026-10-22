package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.ImportReferenceUpdater;
import self.research.ontology.owlEditor.service.ImportReferenceUpdater.Result;
import self.research.ontology.owlEditor.service.ImportReferenceUpdater.Skipped;
import self.research.ontology.owlEditor.service.OntologyMetadataService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyIriControllerTest {

    private OntologyMetadataService metadata;
    private ImportReferenceUpdater imports;
    private OntologyIriController controller;

    @BeforeEach
    void setUp() {
        metadata = mock(OntologyMetadataService.class);
        imports = mock(ImportReferenceUpdater.class);
        controller = new OntologyIriController(metadata, imports);
        when(metadata.currentOntologyIri("p1", false, "me@x.org")).thenReturn("http://x.org/old");
    }

    private static Map<String, String> body(String draft) {
        return Map.of("ontologyIri", "http://x.org/new", "versionIri", "", "draft", draft, "userId", "me@x.org", "username", "Me");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aPublicChangeUpdatesImportersAndReportsThoseItCouldNotUpdate() {
        Skipped skipped = new Skipped("menu", "Menu", "sam@x.org", "You don't have edit rights in this project");
        when(imports.update(eq("p1"), eq("http://x.org/old"), eq("http://x.org/new"), any(), any()))
                .thenReturn(new Result(List.of(new ImportReferenceUpdater.Updated("restaurant", "Restaurant")), List.of(skipped)));

        ResponseEntity<?> response = controller.updateOntologyIRIs("p1", body("false"), new MockHttpServletRequest());

        Map<String, Object> result = (Map<String, Object>) response.getBody();
        assertEquals(true, result.get("success"));
        assertEquals(List.of(skipped), result.get("skippedImports"));
        assertEquals(1, ((List<?>) result.get("updatedImports")).size());
        verify(metadata).updateOntologyIRIs("p1", "http://x.org/new", "", false, "me@x.org", "Me");
    }

    @Test
    void theActorComesFromTheLoginTokenNotFromTheRequestBody() {
        when(imports.update(anyString(), anyString(), anyString(), any(), any())).thenReturn(Result.none());
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"email\":\"real@x.org\",\"userId\":\"user-7\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");

        controller.updateOntologyIRIs("p1", body("false"), request);

        ArgumentCaptor<ImportReferenceUpdater.Actor> actor = ArgumentCaptor.forClass(ImportReferenceUpdater.Actor.class);
        verify(imports).update(eq("p1"), anyString(), anyString(), actor.capture(), any());
        assertEquals("user-7", actor.getValue().userId());
        assertEquals("real@x.org", actor.getValue().email());
    }

    @Test
    void aDraftChangeNeverTouchesOtherProjects() {
        ResponseEntity<?> response = controller.updateOntologyIRIs("p1", body("true"), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        verify(imports, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void theDraftFlagInTheUrlKeepsTheChangeInTheDraftEvenWithoutABodyFlag() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("draft", "true");

        ResponseEntity<?> response = controller.updateOntologyIRIs("p1",
                Map.of("ontologyIri", "http://x.org/new", "versionIri", "", "userId", "me@x.org", "username", "Me"), request);

        assertEquals(200, response.getStatusCode().value());
        verify(metadata).updateOntologyIRIs("p1", "http://x.org/new", "", true, "me@x.org", "Me");
        verify(metadata, never()).updateOntologyIRIs(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(false), any(), any());
        verify(imports, never()).update(anyString(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFailureIsReportedWithoutTouchingOtherProjects() {
        doThrow(new IllegalStateException("store offline")).when(metadata)
                .updateOntologyIRIs(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        Map<String, Object> result = (Map<String, Object>) controller.updateOntologyIRIs("p1", body("false"),
                new MockHttpServletRequest()).getBody();

        assertEquals(false, result.get("success"));
        verify(imports, never()).update(anyString(), any(), any(), any(), any());
    }
}
