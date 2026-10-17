package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.vocab.XSDVocabulary;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasoningJobExecutorExplanationTest {

    @Test
    void findsJustificationsForAnInconsistentOntologyThatUsesXsdDate() throws Exception {
        List<?> justifications = justificationsFor("regular");

        assertFalse(justifications.isEmpty());
    }

    @Test
    void findsLaconicJustificationsForAnInconsistentOntologyThatUsesXsdDate() throws Exception {
        List<?> justifications = justificationsFor("laconic");

        assertFalse(justifications.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<?> justificationsFor(String mode) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:dates"));
        OWLClass adult = df.getOWLClass(IRI.create("urn:test#Adult"));
        OWLClass child = df.getOWLClass(IRI.create("urn:test#Child"));
        OWLNamedIndividual alex = df.getOWLNamedIndividual(IRI.create("urn:test#alex"));
        OWLDataProperty bornOn = df.getOWLDataProperty(IRI.create("urn:test#bornOn"));
        manager.addAxioms(ontology, Set.of(
                df.getOWLDisjointClassesAxiom(adult, child),
                df.getOWLClassAssertionAxiom(adult, alex),
                df.getOWLClassAssertionAxiom(child, alex),
                df.getOWLDataPropertyAssertionAxiom(bornOn, alex,
                        df.getOWLLiteral("2015-04-01", df.getOWLDatatype(XSDVocabulary.DATE.getIRI())))));

        OWLReasoner reasoner = EphemeralReasonerFactory.create(ontology, ReasonerType.HERMIT, 60_000);
        OntologySessionService sessions = mock(OntologySessionService.class);
        when(sessions.openSession(eq("P1"), any(), any())).thenReturn(new OntologySessionService.ReasoningSession(
                manager, ontology, reasoner, ReasonerType.HERMIT, null, false, null, 3));

        ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, new ReasoningSnapshotService(sessions, ReasoningSnapshotStore.disabled(), 6, 120, 1.0));
        ReflectionTestUtils.setField(executor, "justificationTimeoutMs", 15_000L);
        ReflectionTestUtils.setField(executor, "defaultMaxJustifications", 5);

        Map<String, Object> result = executor.execute(ReasoningJob.builder()
                .jobId("job-1")
                .jobType(ReasoningJob.JobType.REASONER_EXPLAIN_INCONSISTENCY)
                .projectId("P1")
                .reasonerType("HERMIT")
                .explanationMode(mode)
                .build());

        assertEquals(true, result.get("success"));
        assertEquals(false, result.get("isConsistent"));
        List<Map<String, Object>> causes = (List<Map<String, Object>>) result.get("causes");
        Map<String, Object> justificationCause = causes.stream()
                .filter(cause -> "JUSTIFICATIONS".equals(cause.get("type")))
                .findFirst()
                .orElse(null);
        assertNotNull(justificationCause, "expected a JUSTIFICATIONS cause, got " + causes);
        return (List<?>) justificationCause.get("justifications");
    }
}
