package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.common.ReasoningSnapshot;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasoningJobExecutorResultTest {

    @Test
    void inferredAxiomsReportAPartialTotalWhenTheSearchStopsEarly() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:chain"));
        Set<OWLAxiom> axioms = new HashSet<>();
        for (int i = 0; i < 120; i++) {
            axioms.add(df.getOWLSubClassOfAxiom(cls(df, i), cls(df, i + 1)));
        }
        manager.addAxioms(ontology, axioms);

        Map<String, Object> result = run(manager, ontology, ReasonerType.STRUCTURAL,
                ReasoningJob.JobType.REASONER_INFERRED_AXIOMS);

        int limit = ReasoningSnapshot.DEFAULT_INFERRED_AXIOM_LIMIT;
        assertEquals(limit, ((List<?>) result.get("axioms")).size());
        assertEquals(true, result.get("totalIsPartial"));
        assertTrue(((Number) result.get("totalInferredAxioms")).intValue() > limit);
        assertTrue(result.get("message").toString().contains("at least"));
    }

    @Test
    void inferredAxiomsSayShowingAllOnlyWhenEverythingIsShown() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:small"));
        manager.addAxioms(ontology, Set.of(
                df.getOWLSubClassOfAxiom(cls(df, 0), cls(df, 1)),
                df.getOWLSubClassOfAxiom(cls(df, 1), cls(df, 2))));

        Map<String, Object> result = run(manager, ontology, ReasonerType.STRUCTURAL,
                ReasoningJob.JobType.REASONER_INFERRED_AXIOMS);

        assertEquals(3, ((List<?>) result.get("axioms")).size());
        assertFalse(result.containsKey("totalIsPartial"));
        assertEquals("Showing all 3 inferred axioms", result.get("message"));
        assertEquals(7L, result.get("ontologyRevision"));
    }

    @Test
    void inconsistentClassifyResultStillCarriesTheOntologyRevision() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:clash"));
        OWLNamedIndividual alex = df.getOWLNamedIndividual(IRI.create("urn:test#alex"));
        manager.addAxioms(ontology, Set.of(
                df.getOWLDisjointClassesAxiom(cls(df, 0), cls(df, 1)),
                df.getOWLClassAssertionAxiom(cls(df, 0), alex),
                df.getOWLClassAssertionAxiom(cls(df, 1), alex)));

        Map<String, Object> classify = run(manager, ontology, ReasonerType.HERMIT,
                ReasoningJob.JobType.REASONER_CLASSIFY);
        assertEquals(true, classify.get("inconsistent"));
        assertEquals(7L, classify.get("ontologyRevision"));

        Map<String, Object> inferred = run(manager, ontology, ReasonerType.HERMIT,
                ReasoningJob.JobType.REASONER_INFERRED_AXIOMS);
        assertEquals(true, inferred.get("inconsistent"));
        assertEquals(7L, inferred.get("ontologyRevision"));
    }

    private static OWLClass cls(OWLDataFactory df, int i) {
        return df.getOWLClass(IRI.create("urn:test#C" + String.format("%03d", i)));
    }

    private static Map<String, Object> run(OWLOntologyManager manager, OWLOntology ontology, ReasonerType type,
                                           ReasoningJob.JobType jobType) throws Exception {
        OntologySessionService sessions = mock(OntologySessionService.class);
        when(sessions.currentRevision("P1")).thenReturn(7L);
        when(sessions.openSession(eq("P1"), any(), any())).thenAnswer(invocation ->
                new OntologySessionService.ReasoningSession(null, ontology,
                        EphemeralReasonerFactory.create(ontology, type, 60_000), type, null, false, null, 7));
        when(sessions.openSnapshotSession(eq("P1"), any(), any())).thenAnswer(invocation ->
                new OntologySessionService.ReasoningSession(null, ontology,
                        EphemeralReasonerFactory.create(ontology, type, 60_000), type, null, false, null, 7));
        ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, new ReasoningSnapshotService(sessions, ReasoningSnapshotStore.disabled(), 6, 120, 1.0));
        return executor.execute(ReasoningJob.builder()
                .jobId("job-1")
                .jobType(jobType)
                .projectId("P1")
                .reasonerType(type.name())
                .build());
    }
}
