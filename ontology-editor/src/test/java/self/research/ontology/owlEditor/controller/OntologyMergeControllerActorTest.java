package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeResult;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;
import self.research.ontology.owlEditor.service.OntologyMergeService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyMergeControllerActorTest {

    @Test
    void aMergeIsRecordedUnderTheSignedInUserNotAnonymous() throws Exception {
        OntologyMergeService merge = mock(OntologyMergeService.class);
        when(merge.mergeOntologies(anyString(), any(Path.class), any(), any(), any(), any(MergeOptions.class), anyString(), anyString()))
                .thenReturn(new MergeResult());
        OntologyMergeController controller = new OntologyMergeController(merge, mock(ProjectMetadataService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"email\":\"real@x.org\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");

        controller.executeMerge("p1", new MockMultipartFile("file", "a.owl", "text/plain", "x".getBytes()),
                MergeStrategy.SIMPLE_UNION, null, null, null, null, null, null, null, request);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(merge).mergeOntologies(anyString(), any(Path.class), any(), isNull(), isNull(), any(MergeOptions.class),
                user.capture(), anyString());
        assertEquals("real@x.org", user.getValue());
    }
}
