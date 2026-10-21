package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.model.merge.MergeAnalysisResult;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeResult;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;
import self.research.ontology.owlEditor.service.MergeTargetResolver;
import self.research.ontology.owlEditor.service.OntologyMergeService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyMergeControllerAccessTest {

    private OntologyMergeService merge;
    private ProjectMetadataService metadata;
    private ProjectAccessService access;
    private OntologyMergeController controller;

    @BeforeEach
    void setUp() throws Exception {
        merge = mock(OntologyMergeService.class);
        metadata = mock(ProjectMetadataService.class);
        access = mock(ProjectAccessService.class);
        when(metadata.readStatus("victim")).thenReturn(Optional.of(mock(ProjectStatus.class)));
        when(merge.mergeOntologies(anyString(), any(Path.class), any(), any(), any(), any(MergeOptions.class), anyString(), anyString(), anyBoolean()))
                .thenReturn(new MergeResult());
        when(merge.analyzeOntologies(anyString(), any(Path.class), any(), any(), any()))
                .thenReturn(new MergeAnalysisResult());
        controller = new OntologyMergeController(merge, new MergeTargetResolver(metadata, new ProjectAuthorization(access, false)));
    }

    private static MockHttpServletRequest signedIn(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "a.owl", "text/plain", "x".getBytes());
    }

    @Test
    void mergingIntoAnotherProjectTheCallerCannotAccessIsRefused() throws Exception {
        when(access.hasProjectAccess("victim", "mallory@x.org")).thenReturn(false);

        var response = controller.executeMerge("mine", upload(), MergeStrategy.SIMPLE_UNION, null, "victim", null, null,
                null, null, null, false, signedIn("mallory@x.org"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(merge, never()).mergeOntologies(anyString(), any(Path.class), any(), any(), any(), any(MergeOptions.class),
                anyString(), anyString(), anyBoolean());
    }

    @Test
    void analysingAnotherProjectTheCallerCannotAccessIsRefused() throws Exception {
        when(access.hasProjectAccess("victim", "mallory@x.org")).thenReturn(false);

        var response = controller.analyzeMerge("mine", upload(), "victim", null, false, null, signedIn("mallory@x.org"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void mergingIntoAnotherProjectTheCallerCanAccessIsAllowed() throws Exception {
        when(access.hasProjectAccess("victim", "owner@x.org")).thenReturn(true);

        var response = controller.executeMerge("mine", upload(), MergeStrategy.SIMPLE_UNION, null, "victim", null, null,
                null, null, null, false, signedIn("owner@x.org"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void mergingIntoTheProjectInThePathNeedsNoExtraCheck() throws Exception {
        var response = controller.executeMerge("mine", upload(), MergeStrategy.SIMPLE_UNION, null, null, null, null,
                null, null, null, false, signedIn("owner@x.org"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(access, never()).hasProjectAccess(anyString(), anyString());
    }

    @Test
    void aCallerWithNoSignedInIdentityIsUnauthenticatedWhenMergingIntoAnotherProject() throws Exception {
        var response = controller.executeMerge("mine", upload(), MergeStrategy.SIMPLE_UNION, null, "victim", null, null,
                null, null, null, false, new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    private static MockHttpServletRequest signedInWithId(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void whenTheTokenHasAUserIdTheTargetIsCheckedByUserId() throws Exception {
        when(access.hasProjectAccess("victim", "u-7", "owner@x.org")).thenReturn(true);

        var response = controller.executeMerge("mine", upload(), MergeStrategy.SIMPLE_UNION, null, "victim", null, null,
                null, null, null, false, signedInWithId("owner@x.org", "u-7"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void aTokenWithAUserIdWithoutAccessIsRefused() throws Exception {
        when(access.hasProjectAccess("victim", "u-9", "mallory@x.org")).thenReturn(false);

        var response = controller.executeMerge("mine", upload(), MergeStrategy.SIMPLE_UNION, null, "victim", null, null,
                null, null, null, false, signedInWithId("mallory@x.org", "u-9"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
