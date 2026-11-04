package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplainInferenceJobTest {

    private static final String NS = "http://example.org/explain#";
    private static final String TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private OWLOntology chain() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/explain"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "Graduate")),
                df.getOWLClass(IRI.create(NS + "Student"))));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "Student")),
                df.getOWLClass(IRI.create(NS + "Person"))));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(df.getOWLClass(IRI.create(NS + "Graduate")),
                df.getOWLNamedIndividual(IRI.create(NS + "ann"))));
        return ontology;
    }

    private OWLOntology hard() throws Exception {
        OWLOntology hard = HermitInterruptTest.pigeonhole(12, 11);
        OWLDataFactory df = hard.getOWLOntologyManager().getOWLDataFactory();
        hard.getOWLOntologyManager().addAxiom(hard, df.getOWLClassAssertionAxiom(df.getOWLClass(IRI.create(NS + "Q")),
                df.getOWLNamedIndividual(IRI.create(NS + "ann"))));
        hard.getOWLOntologyManager().addAxiom(hard, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "Q")),
                df.getOWLClass(IRI.create("http://example.org/P0H0"))));
        return hard;
    }

    private ReasoningJob job(String owner) {
        return ReasoningJob.builder()
                .jobId("job-1")
                .jobType(ReasoningJob.JobType.EXPLAIN_INFERENCE)
                .projectId("P1")
                .reasonerType("HERMIT")
                .entailmentType("ClassAssertion")
                .subjectIri(NS + "ann")
                .propertyIri(TYPE)
                .targetIri(NS + "Person")
                .ownerEmail(owner)
                .build();
    }

    private OntologySessionService sessions(OWLOntology ontology, long triples) {
        OntologySessionService sessions = mock(OntologySessionService.class);
        when(sessions.currentTripleCount("P1")).thenReturn(triples);
        when(sessions.currentRevision("P1")).thenReturn(7L);
        when(sessions.loadForParse(any(), any())).thenReturn(ontology);
        return sessions;
    }

    private ReasoningJobExecutor executor(OntologySessionService sessions) {
        return new ReasoningJobExecutor(sessions,
                new ReasoningSnapshotService(sessions, ReasoningSnapshotStore.disabled(), 6, 120, 1.0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explainJobReturnsTheJustificationInTheDocumentedShape() throws Exception {
        OntologySessionService sessions = sessions(chain(), 100);

        Map<String, Object> result = executor(sessions).execute(job(null));

        assertEquals(true, result.get("success"));
        assertEquals("EXPLAINED", result.get("explainStatus"));
        assertEquals("HermiT", result.get("reasoner"));
        assertEquals("HermiT", result.get("reasonerRequested"));
        assertEquals(false, result.get("reasonerFallback"));
        assertEquals(true, result.get("minimal"));
        assertEquals(false, result.get("partial"));
        assertEquals(false, result.get("timeBudgetHit"));
        assertEquals(false, result.get("usesRules"));
        assertEquals(3, result.get("axiomCount"));
        assertEquals(7L, result.get("ontologyRevision"));
        assertNotNull(result.get("elapsedMs"));
        assertNotNull(result.get("loadMs"));
        assertEquals(30_000L, result.get("budgetMs"));
        Map<String, Object> conclusion = (Map<String, Object>) result.get("conclusion");
        assertEquals("ClassAssertion", conclusion.get("axiomType"));
        assertEquals("CONCLUSION", conclusion.get("role"));
        assertEquals(NS + "ann", conclusion.get("subjectIri"));
        assertEquals(TYPE, conclusion.get("predicateIri"));
        assertEquals(NS + "Person", conclusion.get("objectIri"));
        List<Map<String, Object>> justification = (List<Map<String, Object>>) result.get("justification");
        assertEquals(3, justification.size());
        assertTrue(justification.stream().allMatch(entry -> "ASSERTED".equals(entry.get("role"))));
        Map<String, Object> subclass = justification.stream()
                .filter(entry -> "SubClassOf".equals(entry.get("axiomType")) && (NS + "Student").equals(entry.get("subjectIri")))
                .findFirst().orElseThrow();
        assertEquals(NS + "Person", subclass.get("objectIri"));
        assertEquals("http://www.w3.org/2000/01/rdf-schema#subClassOf", subclass.get("predicateIri"));
        assertTrue(subclass.get("text").toString().contains("SubClassOf"));
        assertNotNull(subclass.get("entities"));
        assertFalse(result.containsKey("status"));
    }

    @Test
    void draftOwnerIsPassedToTheLoadAndTheSharedRevisionIsLeftOut() throws Exception {
        OntologySessionService sessions = sessions(chain(), 100);

        Map<String, Object> result = executor(sessions).execute(job("user-1"));

        verify(sessions).loadForParse("P1", "user-1");
        assertFalse(result.containsKey("ontologyRevision"));
        assertEquals("EXPLAINED", result.get("explainStatus"));
    }

    @Test
    void publishedLoadUsesNoOwner() throws Exception {
        OntologySessionService sessions = sessions(chain(), 100);

        executor(sessions).execute(job(null));

        verify(sessions).loadForParse("P1", null);
    }

    @Test
    void oversizedProjectIsRefusedBeforeItIsLoaded() throws Exception {
        OntologySessionService sessions = sessions(chain(), 5_000_000);

        Map<String, Object> result = executor(sessions).execute(job(null));

        assertEquals(true, result.get("success"));
        assertEquals("TOO_LARGE", result.get("explainStatus"));
        assertTrue(result.get("message").toString().contains("ELK"));
        verify(sessions, never()).loadForParse(any(), any());
    }

    @Test
    void unknownSizeIsAFailureNotALoad() throws Exception {
        OntologySessionService sessions = sessions(chain(), -1);

        Map<String, Object> result = executor(sessions).execute(job(null));

        assertEquals(false, result.get("success"));
        verify(sessions, never()).loadForParse(any(), any());
    }

    @Test
    void notEntailedFactIsACompletedAnswerNotAnError() throws Exception {
        OntologySessionService sessions = sessions(chain(), 100);
        ReasoningJob job = job(null);
        job.setTargetIri(NS + "Pet");

        Map<String, Object> result = executor(sessions).execute(job);

        assertEquals(true, result.get("success"));
        assertEquals("NOT_ENTAILED", result.get("explainStatus"));
        assertEquals(List.of(), result.get("justification"));
    }

    @Test
    void unsupportedKindIsACompletedAnswerWithAClearMessage() throws Exception {
        OntologySessionService sessions = sessions(chain(), 100);
        ReasoningJob job = job(null);
        job.setEntailmentType("DisjointClasses");

        Map<String, Object> result = executor(sessions).execute(job);

        assertEquals("UNSUPPORTED", result.get("explainStatus"));
        assertEquals(true, result.get("success"));
    }

    @Test
    void jobsLifecycleThroughTheQueueEndsCompletedWithTheExplanation() throws Exception {
        OntologySessionService sessions = sessions(chain(), 100);
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor(sessions), pool,
                new RunawayReasoningHealthIndicator(), 60_000, 5_000);

        ReasoningJob job = manager.enqueue(ReasoningJob.JobType.EXPLAIN_INFERENCE, "P1", null, null, "HERMIT", null,
                null, null, "ClassAssertion", NS + "ann", NS + "Person", TYPE, null, (Map<String, Object>) null);
        processor.processNext();

        assertTrue(waitUntil(() -> manager.getJob(job.getJobId()).getStatus() == ReasoningJob.Status.COMPLETED, 20_000));
        ReasoningJob done = manager.getJob(job.getJobId());
        assertEquals("EXPLAINED", done.getResult().get("explainStatus"));
        assertTrue(waitUntil(() -> manager.activeJobCount() == 0, 5_000));
    }

    @Test
    void identicalPendingExplainRequestsShareOneJobButDifferentDatatypesDoNot() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob first = manager.enqueue(ReasoningJob.JobType.EXPLAIN_INFERENCE, "P1", null, null, "HERMIT", null,
                null, null, "DataPropertyAssertion", NS + "ann", null, NS + "age", "41", Map.of("literalDatatypeIri", XSD + "integer"));
        ReasoningJob same = manager.enqueue(ReasoningJob.JobType.EXPLAIN_INFERENCE, "P1", null, null, "HERMIT", null,
                null, null, "DataPropertyAssertion", NS + "ann", null, NS + "age", "41", Map.of("literalDatatypeIri", XSD + "integer"));
        ReasoningJob other = manager.enqueue(ReasoningJob.JobType.EXPLAIN_INFERENCE, "P1", null, null, "HERMIT", null,
                null, null, "DataPropertyAssertion", NS + "ann", null, NS + "age", "41", Map.of("literalDatatypeIri", XSD + "string"));

        assertSame(first, same);
        assertEquals(2, first.getRequesterCount());
        assertNotEquals(first.getJobId(), other.getJobId());
        assertEquals(XSD + "string", other.getParams().get("literalDatatypeIri"));
    }

    @Test
    void cancellingARunningExplanationStopsTheReasonerAndFreesTheSlot() throws Exception {
        OntologySessionService sessions = sessions(hard(), 100);
        ReasoningJobExecutor executor = executor(sessions);
        ReflectionTestUtils.setField(executor, "explainBudgetMs", 120_000L);
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor, pool,
                new RunawayReasoningHealthIndicator(), 300_000, 20_000);

        ReasoningJob job = manager.enqueue(ReasoningJob.JobType.EXPLAIN_INFERENCE, "P1", null, null, "HERMIT", null,
                null, null, "ClassAssertion", NS + "ann", NS + "Person", TYPE, null, (Map<String, Object>) null);
        processor.processNext();
        assertTrue(waitUntil(() -> manager.getJob(job.getJobId()).getStatus() == ReasoningJob.Status.PROCESSING, 5_000));
        Thread.sleep(500);

        ReasoningQueueManager.CancelResult result = processor.cancel(job.getJobId());

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING, result.outcome());
        assertTrue(waitUntil(() -> manager.activeJobCount() == 0, 20_000));
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());
        assertEquals(ReasoningQueueManager.CANCELLED_MESSAGE, manager.getJob(job.getJobId()).getError());
        assertNull(manager.getJob(job.getJobId()).getResult());
    }

    @Test
    void aTinyBudgetOnAHardOntologyCompletesTheJobWithATimeoutAnswer() throws Exception {
        OntologySessionService sessions = sessions(hard(), 100);
        ReasoningJobExecutor executor = executor(sessions);
        ReflectionTestUtils.setField(executor, "explainBudgetMs", 400L);

        long begin = System.currentTimeMillis();
        Map<String, Object> result = executor.execute(job(null));

        assertTrue(System.currentTimeMillis() - begin < 20_000);
        assertEquals(true, result.get("success"));
        assertEquals("TIMEOUT", result.get("explainStatus"));
        assertEquals(true, result.get("timeBudgetHit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unionFactIsDescribedWithItsOperandsInTheResult() throws Exception {
        OWLOntology ontology = chain();
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "Student")), df.getOWLClass(IRI.create(NS + "Learner"))));
        OntologySessionService sessions = sessions(ontology, 100);
        ReasoningJob job = job(null);
        job.setTargetIri(null);
        job.setParams(Map.of("objectIris", List.of(NS + "Learner", NS + "Teacher"), "objectOperator", "UNION"));

        Map<String, Object> result = executor(sessions).execute(job);

        assertEquals("EXPLAINED", result.get("explainStatus"));
        Map<String, Object> conclusion = (Map<String, Object>) result.get("conclusion");
        assertNull(conclusion.get("objectIri"));
        assertEquals(List.of(NS + "Learner", NS + "Teacher"), conclusion.get("objectIris"));
        assertEquals("UNION", conclusion.get("objectOperator"));
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
