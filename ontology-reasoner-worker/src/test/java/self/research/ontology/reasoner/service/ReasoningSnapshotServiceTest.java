package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReasoningSnapshotServiceTest {

    private static final String PIZZA_NS = "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#";

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void sameRevisionIsBuiltOnceAndThenServedFromTheCache() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> first = snapshots.snapshot("P1", "hermit", null);
        Map<String, Object> second = snapshots.snapshot("P1", "HERMIT", "");

        assertEquals(1, snapshots.buildCount());
        verify(sessions, times(1)).openSnapshotSession(any(), any(), any());
        assertEquals(false, first.get("cached"));
        assertEquals(true, second.get("cached"));
        assertEquals(true, first.get("success"));
        assertEquals(true, first.get("consistent"));
        assertEquals("HermiT", first.get("reasonerType"));
        assertEquals("HermiT", first.get("propertyReasonerType"));
        assertEquals("HERMIT", first.get("requestedReasoner"));
        assertEquals(1L, first.get("ontologyRevision"));
        assertEquals(false, first.get("draft"));
        assertFalse(first.containsKey("downgradedWarning"));
        assertTrue(first.keySet().containsAll(Set.of("classTree", "classTreeVisited", "classChildren", "classNodes",
                "classHierarchyFlat", "equivalentClasses", "objectPropertyTree", "dataPropertyTree",
                "objectPropertyHierarchyFlat", "dataPropertyHierarchyFlat", "stats", "inferredAxioms",
                "unsatisfiableClasses", "unsatisfiableKnown", "totalClasses", "durationMs")));
        assertEquals(first.get("classTree"), second.get("classTree"));
    }

    @Test
    void newRevisionIsBuiltAgainAndTheOldOneIsDropped() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        snapshots.snapshot("P1", "OPENLLET", null);
        editor.bumpRevision("P1");
        Map<String, Object> next = snapshots.snapshot("P1", "OPENLLET", null);

        assertEquals(2, snapshots.buildCount());
        assertEquals(2L, next.get("ontologyRevision"));
        assertEquals(false, next.get("cached"));
        assertEquals(1, snapshots.size());
    }

    @Test
    void revisionZeroIsNeverCachedOrShared() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA)
                .revision("P1", 0);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> first = snapshots.snapshot("P1", "OPENLLET", null);
        Map<String, Object> second = snapshots.snapshot("P1", "OPENLLET", null);

        assertEquals(2, snapshots.buildCount());
        assertEquals(2, editor.loads.get());
        assertEquals(false, first.get("cached"));
        assertEquals(false, second.get("cached"));
        assertEquals(true, second.get("consistent"));
        assertEquals(0L, second.get("ontologyRevision"));
        assertEquals(0, snapshots.size());
        assertTrue(snapshots.cached("P1", "OPENLLET", editor.tripleCount("P1")).isEmpty());
        assertTrue(snapshots.cachedOrInFlight("P1", "OPENLLET").isEmpty());
    }

    @Test
    void sameRevisionWithADifferentTripleCountIsBuiltAgain() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        Map<String, Object> before = snapshots.snapshot("P1", "OPENLLET", null);
        long triplesBefore = editor.tripleCount("P1");
        editor.project("P1", SnapshotTestSupport.CONSISTENT);
        Map<String, Object> after = snapshots.snapshot("P1", "OPENLLET", null);

        assertTrue(editor.tripleCount("P1") != triplesBefore);
        assertEquals(2, snapshots.buildCount());
        assertEquals(2, editor.loads.get());
        assertEquals(false, after.get("cached"));
        assertEquals(1L, after.get("ontologyRevision"));
        assertTrue(!before.get("totalClasses").equals(after.get("totalClasses")));
        assertEquals(1, snapshots.size());
        assertTrue(snapshots.cached("P1", "OPENLLET", triplesBefore).isEmpty());
        assertEquals(true, snapshots.snapshot("P1", "OPENLLET", null).get("cached"));
    }

    @Test
    void differentReasonersAreCachedSeparately() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        snapshots.snapshot("P1", "OPENLLET", null);
        Map<String, Object> elk = snapshots.snapshot("P1", "ELK", null);

        assertEquals(2, snapshots.buildCount());
        assertEquals("ELK", elk.get("requestedReasoner"));
        long triples = editor.tripleCount("P1");
        assertEquals(true, snapshots.cached("P1", "elk", triples).orElseThrow().get("cached"));
        assertEquals(true, snapshots.cached("P1", null, triples).orElseThrow().get("cached"));
        assertTrue(snapshots.cached("P1", "HERMIT", triples).isEmpty());
        assertTrue(snapshots.cached("P1", "elk", triples + 1).isEmpty());
    }

    @Test
    void ownerSnapshotsAreNeverCachedOrShared() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA)
                .draft("alice@example.com", SnapshotTestSupport.INCONSISTENT);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        Map<String, Object> draft = snapshots.snapshot("P1", "OPENLLET", "alice@example.com");
        Map<String, Object> again = snapshots.snapshot("P1", "OPENLLET", "alice@example.com");
        Map<String, Object> published = snapshots.snapshot("P1", "OPENLLET", null);

        assertEquals(3, snapshots.buildCount());
        assertEquals(false, draft.get("consistent"));
        assertEquals(true, draft.get("draft"));
        assertEquals(-1L, draft.get("ontologyRevision"));
        assertEquals(false, again.get("cached"));
        assertEquals(true, published.get("consistent"));
        assertEquals(false, published.get("cached"));
        assertEquals(1, snapshots.size());
    }

    @Test
    void elkOnASmallOntologyTakesThePropertyTreesFromOpenllet() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        Map<String, Object> snapshot = snapshots.snapshot("P1", "ELK", null);

        assertEquals("ELK", snapshot.get("reasonerType"));
        assertEquals("Openllet", snapshot.get("propertyReasonerType"));
        Map<String, Object> objectRoot = root(snapshot, "objectPropertyTree");
        Map<String, Object> hasIngredient = child(objectRoot, PIZZA_NS + "hasIngredient");
        assertNotNull(hasIngredient, "hasIngredient should sit under owl:topObjectProperty");
        assertEquals(Set.of(PIZZA_NS + "hasBase", PIZZA_NS + "hasTopping"), childIds(hasIngredient));
        assertTrue(childIds(root(snapshot, "dataPropertyTree")).contains(PIZZA_NS + "hasCaloricContent"));
    }

    @Test
    void largeOntologyUsesElkForClassesAndProperties() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor, 10));

        Map<String, Object> snapshot = snapshots.snapshot("P1", "HERMIT", null);

        assertEquals("ELK", snapshot.get("reasonerType"));
        assertEquals("ELK", snapshot.get("propertyReasonerType"));
        assertEquals("HERMIT", snapshot.get("requestedReasoner"));
        assertNotNull(snapshot.get("downgradedWarning"));
    }

    @Test
    void hermitThatCannotBeLoadedFallsBackToOpenllet() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        doThrow(new RuntimeException("HermiT reasoner could not be started", new NoClassDefFoundError("org/semanticweb/HermiT/Reasoner")))
                .when(sessions).createReasoner(any(), eq(ReasonerType.HERMIT));
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> snapshot = snapshots.snapshot("P1", "HERMIT", null);

        assertEquals("Openllet", snapshot.get("reasonerType"));
        assertEquals("Openllet", snapshot.get("propertyReasonerType"));
        assertEquals("HERMIT", snapshot.get("requestedReasoner"));
        assertTrue(snapshot.get("downgradedWarning").toString().contains("org/semanticweb/HermiT/Reasoner"));
        assertEquals(true, snapshot.get("consistent"));
    }

    @Test
    void hermitRefusingSwrlBuiltInsFallsBackToOpenllet() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().project("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        Map<String, Object> snapshot = snapshots.snapshot("P1", "HERMIT", null);

        assertEquals("Openllet", snapshot.get("reasonerType"));
        assertTrue(snapshot.get("downgradedWarning").toString().contains("built-in"));
        assertEquals(true, snapshot.get("consistent"));
        assertEquals(true, snapshots.snapshot("P1", "HERMIT", null).get("cached"));
    }

    @Test
    void stoppedHermitStartIsNotTurnedIntoAnOpenlletRun() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        doThrow(new ReasonerInterruptedException("Reasoning was stopped"))
                .when(sessions).createReasoner(any(), eq(ReasonerType.HERMIT));
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        assertThrows(ReasonerInterruptedException.class, () -> snapshots.snapshot("P1", "HERMIT", null));
        verify(sessions, never()).createReasoner(any(), eq(ReasonerType.OPENLLET));
        assertEquals(0, snapshots.size());
    }

    @Test
    void inconsistentOntologyGivesAnInconsistentSnapshot() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().project("P1", SnapshotTestSupport.INCONSISTENT);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        Map<String, Object> snapshot = snapshots.snapshot("P1", "HERMIT", null);

        assertEquals(true, snapshot.get("success"));
        assertEquals(false, snapshot.get("consistent"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inconsistency = (Map<String, Object>) snapshot.get("inconsistency");
        assertEquals(true, inconsistency.get("inconsistent"));
        assertFalse(((List<?>) inconsistency.get("issues")).isEmpty());
        assertFalse(snapshot.containsKey("classTree"));
        assertEquals(true, snapshots.snapshot("P1", "HERMIT", null).get("cached"));
    }

    @Test
    void secondRequestWaitsForTheSnapshotThatIsAlreadyBeingBuilt() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        doAnswer(invocation -> {
            if (opened.incrementAndGet() == 1) {
                started.countDown();
                release.await();
            }
            return invocation.callRealMethod();
        }).when(sessions).openSnapshotSession(any(), any(), any());
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Future<Map<String, Object>> first = pool.submit(() -> snapshots.snapshot("P1", "OPENLLET", null));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        AtomicReference<Thread> waiter = new AtomicReference<>();
        Future<Map<String, Object>> second = pool.submit(() -> {
            waiter.set(Thread.currentThread());
            return snapshots.snapshot("P1", "OPENLLET", null);
        });
        assertTrue(ReasoningQueueProcessorTest.waitUntil(() -> waiter.get() != null
                && waiter.get().getState() == Thread.State.WAITING, 10_000));
        release.countDown();

        Map<String, Object> built = first.get(60, TimeUnit.SECONDS);
        Map<String, Object> joined = second.get(60, TimeUnit.SECONDS);

        assertEquals(1, snapshots.buildCount());
        assertEquals(1, opened.get());
        assertEquals(built.get("classTree"), joined.get("classTree"));
        assertEquals(false, joined.get("cached"));
    }

    @Test
    void waitingRequestBuildsTheSnapshotItselfWhenTheFirstBuildIsStopped() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        doAnswer(invocation -> {
            if (opened.incrementAndGet() == 1) {
                started.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ReasonerInterruptedException(e);
                }
            }
            return invocation.callRealMethod();
        }).when(sessions).openSnapshotSession(any(), any(), any());
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Future<Map<String, Object>> first = pool.submit(() -> snapshots.snapshot("P1", "OPENLLET", null));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        AtomicReference<Thread> waiter = new AtomicReference<>();
        Future<Map<String, Object>> second = pool.submit(() -> {
            waiter.set(Thread.currentThread());
            return snapshots.snapshot("P1", "OPENLLET", null);
        });
        assertTrue(ReasoningQueueProcessorTest.waitUntil(() -> waiter.get() != null
                && waiter.get().getState() == Thread.State.WAITING, 10_000));

        first.cancel(true);

        Map<String, Object> rebuilt = second.get(60, TimeUnit.SECONDS);
        assertEquals(true, rebuilt.get("consistent"));
        assertEquals(2, opened.get());
        assertEquals(1, snapshots.size());
    }

    @Test
    void realBuildFailureIsPassedToTheWaitingRequest() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await();
            throw new IllegalStateException("Editor export failed");
        }).when(sessions).openSnapshotSession(any(), any(), any());
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Future<Map<String, Object>> first = pool.submit(() -> snapshots.snapshot("P1", "OPENLLET", null));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        AtomicReference<Thread> waiter = new AtomicReference<>();
        Future<Map<String, Object>> second = pool.submit(() -> {
            waiter.set(Thread.currentThread());
            return snapshots.snapshot("P1", "OPENLLET", null);
        });
        assertTrue(ReasoningQueueProcessorTest.waitUntil(() -> waiter.get() != null
                && waiter.get().getState() == Thread.State.WAITING, 10_000));
        release.countDown();

        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> first.get(30, TimeUnit.SECONDS)).getCause());
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> second.get(30, TimeUnit.SECONDS)).getCause());
        assertEquals(1, snapshots.buildCount());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root(Map<String, Object> snapshot, String key) {
        return ((List<Map<String, Object>>) snapshot.get(key)).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> node, String id) {
        return ((List<Map<String, Object>>) node.get("children")).stream()
                .filter(c -> id.equals(c.get("id")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> childIds(Map<String, Object> node) {
        return ((List<Map<String, Object>>) node.get("children")).stream()
                .map(c -> c.get("id"))
                .collect(Collectors.toSet());
    }
}
