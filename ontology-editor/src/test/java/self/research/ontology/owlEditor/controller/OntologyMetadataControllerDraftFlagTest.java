package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OwlApiFastPathSupport;
import self.research.ontology.owlEditor.service.ProjectImportService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OntologyMetadataControllerDraftFlagTest {

    private final OntologyMetadataService metadata = mock(OntologyMetadataService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final OntologyMutationService mutations = mock(OntologyMutationService.class);
    private OntologyMetadataController controller;

    @BeforeEach
    void setUp() {
        controller = new OntologyMetadataController(metadata, messaging, mock(ProjectImportService.class), mutations,
                mock(OwlApiFastPathSupport.class), null);
    }

    private static MockHttpServletRequest draftUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("draft", "true");
        return request;
    }

    private static int status(Object response) {
        return ((org.springframework.http.ResponseEntity<?>) response).getStatusCode().value();
    }

    @Test
    void annotationsFollowTheDraftFlagInTheUrl() {
        assertEquals(200, status(controller.addAnnotation("p1",
                Map.of("propertyIri", "http://purl.org/dc/terms/title", "value", "New", "userId", "u1"), draftUrl())));
        assertEquals(200, status(controller.updateAnnotation("p1",
                Map.of("propertyIri", "http://purl.org/dc/terms/title", "oldValue", "Old", "newValue", "New", "userId", "u1"),
                draftUrl())));

        verify(metadata).addOntologyAnnotation(eq("p1"), eq("http://purl.org/dc/terms/title"), eq("New"), any(), any(),
                eq(true), eq("u1"), any());
        verify(metadata).updateOntologyAnnotation(eq("p1"), eq("http://purl.org/dc/terms/title"), eq("Old"), eq("New"),
                any(), any(), any(), eq(true), eq("u1"), any());
        verify(metadata, never()).addOntologyAnnotation(anyString(), anyString(), anyString(), any(), any(), eq(false),
                any(), any());
        verifyNoInteractions(messaging);
    }

    @Test
    void importsFollowTheDraftFlagInTheUrl() {
        when(metadata.addOntologyImport(eq("p1"), eq("http://ex.org/other.owl"), anyBoolean(), any(), any(), any()))
                .thenReturn(Map.of("status", "declaredOnly"));

        assertEquals(200, status(controller.addImport("p1", Map.of("importIri", "http://ex.org/other.owl", "userId", "u1"),
                draftUrl())));

        verify(metadata).addOntologyImport(eq("p1"), eq("http://ex.org/other.owl"), eq(true), eq("u1"), any(), any());
        verifyNoInteractions(messaging);
    }

    @Test
    void generalClassAxiomsFollowTheDraftFlagInTheUrl() {
        controller.addGCI("p1", Map.of("subClass", "A and B", "superClass", "C", "userId", "u1"), draftUrl());
        controller.updateGCI("p1", 0, Map.of("oldValue", "A and B SubClassOf C", "subClass", "A", "superClass", "C",
                "userId", "u1"), draftUrl());

        verify(metadata).addGCI("p1", "A and B", "C", true, "u1", null);
        verify(metadata).updateGCI("p1", "A and B SubClassOf C", "A", "C", true, "u1", null);
        verify(metadata, never()).addGCI(anyString(), anyString(), anyString(), eq(false), any(), any());
        verifyNoInteractions(messaging);
    }

    @Test
    void prefixesFollowTheDraftFlagInTheUrl() {
        controller.updatePrefix("p1", Map.of("prefix", "ex", "iri", "http://ex.org/#", "userId", "u1"), draftUrl());

        verify(metadata).updatePrefix(eq("p1"), eq("ex"), eq("http://ex.org/#"), any(), eq(true), eq("u1"), eq("u1"), any());
        verify(metadata, never()).updatePrefix(anyString(), anyString(), anyString(), any(), eq(false), any(), any(), any());
    }
}
