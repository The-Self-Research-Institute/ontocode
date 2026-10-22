package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SparqlDatasetServiceDraftOntologyCacheTest {

    private static final String DRAFT_RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#">
                <owl:Ontology rdf:about="http://example.org/draft"/>
                <owl:Class rdf:about="http://example.org/draft#A"/>
            </rdf:RDF>
            """;

    private SparqlDatasetService service;

    @BeforeEach
    void setUp() {
        service = spy(new SparqlDatasetService());
        DraftSession session = new DraftSession();
        session.setCopyStatus(DraftCopyStatus.READY);
        DraftSessionRepository sessions = mock(DraftSessionRepository.class);
        when(sessions.findByProjectIdAndUserId("proj-1", "user-1")).thenReturn(Optional.of(session));
        ReflectionTestUtils.setField(service, "draftSessionRepository", sessions);
        ReflectionTestUtils.setField(service, "draftMaxTriples", 500_000L);
        doReturn(2L).when(service).countDraftTriples("proj-1", "user-1");
        doReturn(DRAFT_RDF).when(service).exportDraftGraphContent("proj-1", "user-1", RDFFormat.RDFXML);
    }

    @Test
    void sameDraftVersionReusesTheLoadedOntology() throws Exception {
        OWLOntology first = service.loadDraftAsOntology("proj-1", "user-1", 5L);
        OWLOntology second = service.loadDraftAsOntology("proj-1", "user-1", 5L);

        assertSame(first, second);
        verify(service, times(1)).exportDraftGraphContent("proj-1", "user-1", RDFFormat.RDFXML);
    }

    @Test
    void newDraftVersionLoadsTheDraftAgain() throws Exception {
        OWLOntology before = service.loadDraftAsOntology("proj-1", "user-1", 5L);
        OWLOntology after = service.loadDraftAsOntology("proj-1", "user-1", 6L);

        assertNotSame(before, after);
        verify(service, times(2)).exportDraftGraphContent("proj-1", "user-1", RDFFormat.RDFXML);
    }

    @Test
    void writeToTheDraftGraphLoadsTheDraftAgainEvenWithoutAVersionBump() throws Exception {
        OWLOntology before = service.loadDraftAsOntology("proj-1", "user-1", 5L);
        ReflectionTestUtils.invokeMethod(service, "noteGraphWritten", service.getDraftGraphUri("proj-1", "user-1"));
        OWLOntology after = service.loadDraftAsOntology("proj-1", "user-1", 5L);

        assertNotSame(before, after);
    }
}
