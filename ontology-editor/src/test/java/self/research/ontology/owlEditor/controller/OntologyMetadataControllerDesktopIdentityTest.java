package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OwlApiFastPathSupport;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.RequestActor;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OntologyMetadataControllerDesktopIdentityTest {

    private final OntologyMetadataService metadata = mock(OntologyMetadataService.class);
    private OntologyMetadataController controller;

    @BeforeEach
    void setUp() {
        controller = new OntologyMetadataController(metadata, mock(SimpMessagingTemplate.class),
                mock(ProjectImportService.class), mock(OntologyMutationService.class), mock(OwlApiFastPathSupport.class), null);
        RequestActor.useDesktopIdentity(true);
    }

    @AfterEach
    void reset() {
        RequestActor.useDesktopIdentity(false);
    }

    @Test
    void anActiveOntologyAnnotationIsRecordedForTheDesktopUserWhateverThePageSent() {
        controller.addAnnotation("p1",
                Map.of("propertyIri", "http://purl.org/dc/terms/title", "value", "New", "userId", "anonymous"),
                new MockHttpServletRequest());

        verify(metadata).addOntologyAnnotation(eq("p1"), eq("http://purl.org/dc/terms/title"), eq("New"), any(), any(),
                eq(false), eq("desktop-user-local"), any());
    }

    @Test
    void anActiveOntologyAnnotationWithNoUserAtAllIsRecordedForTheDesktopUser() {
        controller.addAnnotation("p1", Map.of("propertyIri", "http://purl.org/dc/terms/title", "value", "New"),
                new MockHttpServletRequest());

        verify(metadata).addOntologyAnnotation(eq("p1"), eq("http://purl.org/dc/terms/title"), eq("New"), any(), any(),
                eq(false), eq("desktop-user-local"), any());
    }
}
