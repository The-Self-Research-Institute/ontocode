package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.PublicEditGuard;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicEditDisabledTest {

    private static ProjectMetadataService metadata(boolean requireDraft, String owner) {
        ProjectMetadataService metadata = mock(ProjectMetadataService.class);
        when(metadata.isRequireDraftForMembers("p1")).thenReturn(requireDraft);
        when(metadata.getOwnerEmail("p1")).thenReturn(Optional.ofNullable(owner));
        return metadata;
    }

    private static OntologyCrudController.MutationRequest request(String userId) {
        return new OntologyCrudController.MutationRequest(
                List.of(new OntologyMutationService.MutationOp("createClass", "http://x/A", "A", null, null, null, null,
                        null, null, null, null, null, null, null, null)), userId, "User", "s1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberSavingPublicWhilePublicEditingIsOffGetsExplicitRejection() {
        OntologyMutationService mutations = mock(OntologyMutationService.class);
        ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
        OntologyCrudController controller = new OntologyCrudController(mutations, mock(DraftTrackingService.class),
                mock(CollaborativeEditService.class), metadata(true, "owner@x.com"), new ProjectWriteLockRegistry(), recorder);

        ResponseEntity<?> response = controller.mutate("p1", request("member@x.com"), false, new MockHttpServletRequest());

        assertEquals(403, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(PublicEditGuard.CODE, body.get("code"));
        assertEquals(true, body.get("requireDraftForMembers"));
        assertTrue(String.valueOf(body.get("error")).contains("Draft Mode"));
        verify(recorder, never()).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any());
    }

    @Test
    void guardLetsOwnerAndOpenProjectsThrough() {
        assertFalse(PublicEditGuard.blocks(metadata(true, "owner@x.com"), "p1", "owner@x.com"));
        assertFalse(PublicEditGuard.blocks(metadata(false, "owner@x.com"), "p1", "member@x.com"));
        assertTrue(PublicEditGuard.blocks(metadata(true, "owner@x.com"), "p1", "member@x.com"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationEditToPublicIsRejectedWhilePublicEditingIsOff() {
        EntityRelationController controller = new EntityRelationController(mock(OntologyMutationService.class),
                mock(DraftTrackingService.class), new ProjectWriteLockRegistry(), mock(ExactChangeRecorder.class),
                metadata(true, "owner@x.com"));
        var req = new EntityRelationController.RelationRequest(
                "add", "http://ex.org/Pizza", "domain", "http://ex.org/Topping", null,
                "member@x.com", "Member", null, null, null, null);

        ResponseEntity<?> response = controller.editRelation("p1", req, false, new MockHttpServletRequest());

        assertEquals(403, response.getStatusCode().value());
        assertEquals(PublicEditGuard.CODE, ((Map<String, Object>) response.getBody()).get("code"));
    }
}
