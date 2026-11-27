package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.impl.IteratingTupleQueryResult;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.owlEditor.service.HierarchyIndexService;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryContext;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClassParentsControllerTest {
    private static final String PROJECT = "test";
    private static final String CHILD = "urn:test:Child";
    private static final String PARENT = "urn:test:Parent";

    @Test
    void parentsViewUsesTheBoundDirectQueryAndFullDetailsStillHaveTheSameRoute() throws Exception {
        var datasets = mock(SparqlDatasetService.class);
        when(datasets.execSelect(eq(PROJECT), argThat(query -> query.contains("<" + CHILD + "> rdfs:subClassOf ?parent")
                && !query.contains("subClassOf+")))).thenReturn(rows());
        var queryService = mock(OntologyQueryService.class);
        when(queryService.classDetails(PROJECT, CHILD)).thenReturn(Map.of("fullDetails", true));
        var fullController = new OntologyQueryController(queryService, mock(ProjectMetadataService.class),
                mock(OntologyMetadataService.class), mock(HierarchyIndexService.class));
        var mvc = MockMvcBuilders.standaloneSetup(fullController, new ClassParentsController(datasets, Optional.empty())).build();

        mvc.perform(get("/api/ontology/classes/details/test").param("classIri", CHILD).param("view", "parents"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].iri").value(PARENT)).andExpect(jsonPath("$.data[0].label").value("Parent"));
        mvc.perform(get("/api/ontology/classes/details/test").param("classIri", CHILD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullDetails").value(true));
    }

    @Test
    void aPrivateDraftReadsTheDraftAwareGraphInsteadOfThePublicOwlApiModel() {
        var datasets = mock(SparqlDatasetService.class);
        var owlApi = mock(OwlApiOntologyContext.class);
        when(datasets.hasActiveDraftOverlay(PROJECT, "drafter")).thenReturn(true);
        when(datasets.execSelect(eq(PROJECT), anyString())).thenAnswer(invocation -> {
            assertEquals("drafter", SparqlQueryContext.getUserId());
            assertEquals(true, SparqlQueryContext.wantsDraft());
            return rows();
        });
        try {
            SparqlQueryContext.setUserId("drafter");
            SparqlQueryContext.setWantsDraft(true);
            assertEquals(List.of(new ClassParentsController.Parent(PARENT, "Parent")),
                    new ClassParentsController(datasets, Optional.of(owlApi)).directParents(PROJECT, CHILD).get("data"));
        } finally {
            SparqlQueryContext.clear();
        }
        verifyNoInteractions(owlApi);
    }

    @Test
    void theLiveOntologyReturnsOnlyNamedAssertedParents() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology();
        var df = manager.getOWLDataFactory();
        var child = df.getOWLClass(IRI.create(CHILD));
        var parent = df.getOWLClass(IRI.create(PARENT));
        var grandparent = df.getOWLClass(IRI.create("urn:test:Grandparent"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(child, parent));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(parent, grandparent));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(child, df.getOWLObjectSomeValuesFrom(
                df.getOWLObjectProperty(IRI.create("urn:test:hasPart")), grandparent)));
        var owlApi = mock(OwlApiOntologyContext.class);
        when(owlApi.ontology(PROJECT)).thenReturn(Optional.of(ontology));
        var datasets = mock(SparqlDatasetService.class);

        assertEquals(List.of(new ClassParentsController.Parent(PARENT, "Parent")),
                new ClassParentsController(datasets, Optional.of(owlApi)).directParents(PROJECT, CHILD).get("data"));
        verifyNoInteractions(datasets);
    }

    private static IteratingTupleQueryResult rows() {
        var vf = SimpleValueFactory.getInstance();
        var immediate = new MapBindingSet();
        immediate.addBinding("parent", vf.createIRI(PARENT));
        immediate.addBinding("label", vf.createLiteral("Parent"));
        var anonymous = new MapBindingSet();
        anonymous.addBinding("parent", vf.createBNode());
        return new IteratingTupleQueryResult(List.of("parent", "label"), List.of(immediate, anonymous));
    }
}
