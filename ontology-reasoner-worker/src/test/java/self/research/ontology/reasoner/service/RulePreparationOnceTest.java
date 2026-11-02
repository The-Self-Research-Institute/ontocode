package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.RuleStage;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulePreparationOnceTest {

    private static final String PROJECT = "triage";

    private static final class CountingStage implements RuleStage {
        final AtomicInteger calls = new AtomicInteger();
        final boolean up;

        CountingStage(boolean up) {
            this.up = up;
        }

        @Override
        public Optional<Set<OWLAxiom>> infer(OWLOntology ontology, Collection<SWRLRule> rules) {
            calls.incrementAndGet();
            return up ? Optional.of(Set.of()) : Optional.empty();
        }
    }

    private OntologySessionService sessions(CountingStage stage) {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().project(PROJECT, "triage-demo.owl");
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        sessions.useRuleStage(stage);
        return sessions;
    }

    @Test
    void theRulesArePreparedOncePerSessionAndTheResultIsMarkedComplete() throws Exception {
        CountingStage stage = new CountingStage(true);
        try (OntologySessionService.ReasoningSession session = sessions(stage).openSnapshotSession(PROJECT, ReasonerType.HERMIT, null)) {
            assertEquals(1, stage.calls.get());
            assertTrue(session.rulesComplete());
            assertTrue(session.downgradedWarning().contains("applied with the rule engine"), session.downgradedWarning());
        }
    }

    @Test
    void anUnavailableRuleEngineMakesTheSessionPartialWithAnHonestNote() throws Exception {
        CountingStage stage = new CountingStage(false);
        try (OntologySessionService.ReasoningSession session = sessions(stage).openSnapshotSession(PROJECT, ReasonerType.HERMIT, null)) {
            assertEquals(1, stage.calls.get());
            assertFalse(session.rulesComplete());
            assertTrue(session.downgradedWarning().contains("could not be reached"), session.downgradedWarning());
        }
    }

    @Test
    void aPartialSnapshotIsNeverKeptSoItIsNotServedAfterTheEngineRecovers() throws Exception {
        CountingStage stage = new CountingStage(false);
        OntologySessionService sessions = sessions(stage);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> first = snapshots.snapshot(PROJECT, "HERMIT", null);
        snapshots.snapshot(PROJECT, "HERMIT", null);

        assertEquals("partial", first.get("completion"));
        assertEquals(0, snapshots.size());
        assertEquals(2, snapshots.buildCount());
    }

    @Test
    void aCompleteSnapshotIsKeptAndReused() throws Exception {
        CountingStage stage = new CountingStage(true);
        OntologySessionService sessions = sessions(stage);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> first = snapshots.snapshot(PROJECT, "HERMIT", null);
        Map<String, Object> second = snapshots.snapshot(PROJECT, "HERMIT", null);

        assertEquals("complete", first.get("completion"));
        assertEquals(1, snapshots.size());
        assertEquals(1, snapshots.buildCount());
        assertEquals(true, second.get("cached"));
    }

    private ReasoningJob dlQuery() {
        return ReasoningJob.builder()
                .jobId("dl")
                .jobType(ReasoningJob.JobType.DL_QUERY)
                .projectId(PROJECT)
                .reasonerType("HERMIT")
                .expression("Child")
                .queryTypes(java.util.List.of("instances"))
                .build();
    }

    @Test
    void aDlQueryReportsPartialWhenTheRuleEngineWasDownAndCompleteWhenItWasUp() throws Exception {
        for (boolean up : new boolean[]{false, true}) {
            OntologySessionService sessions = sessions(new CountingStage(up));
            ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, SnapshotTestSupport.snapshots(sessions));

            Map<String, Object> result = executor.execute(dlQuery());

            assertEquals(true, result.get("success"), String.valueOf(result));
            assertEquals(up ? "complete" : "partial", result.get("completion"));
            assertEquals(up, String.valueOf(result.get("downgradedWarning")).contains("applied with the rule engine"));
        }
    }
}
