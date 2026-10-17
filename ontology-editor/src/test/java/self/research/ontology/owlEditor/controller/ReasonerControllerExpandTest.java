package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonerControllerExpandTest {

    @Test
    @SuppressWarnings("unchecked")
    void expandingANodeOnlyNeedsTheClassHierarchy() throws Exception {
        ReasonerController controller = new ReasonerController();
        ReasonerService reasonerService = mock(ReasonerService.class);
        EditorReasonerCacheService cache = mock(EditorReasonerCacheService.class);
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);

        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        OWLClass dog = ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(IRI.create("http://example.org#Dog"));
        when(cache.getOntology("proj-1")).thenReturn(Optional.of(ontology));
        OWLReasoner reasoner = mock(OWLReasoner.class);
        NodeSet<OWLClass> none = mock(NodeSet.class);
        when(none.getNodes()).thenReturn(Set.of());
        when(reasoner.getSubClasses(any(OWLClass.class), anyBoolean())).thenReturn(none);
        when(reasonerService.getClassHierarchyReasoner(ontology, ReasonerType.OPENLLET)).thenReturn(reasoner);
        when(reasonerService.getInferredSubClasses(eq(reasoner), any(OWLClass.class), eq(true))).thenReturn(Set.of(dog));

        Map<String, Object> body = controller.getInferredSubClasses("proj-1", "http://example.org#Animal",
                "HERMIT", true, null, false, new MockHttpServletRequest()).getBody();

        assertEquals(true, body.get("success"));
        List<Map<String, Object>> children = (List<Map<String, Object>>) body.get("inferredSubClasses");
        assertEquals("http://example.org#Dog", children.get(0).get("iri"));
        verify(reasonerService, never()).getReasoner(any(), any());
    }
}
