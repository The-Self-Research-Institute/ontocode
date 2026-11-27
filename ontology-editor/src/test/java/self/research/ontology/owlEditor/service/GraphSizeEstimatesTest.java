package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSizeEstimatesTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final GraphSizeEstimates sizes = new GraphSizeEstimates(pool, now::get, 200);
    private final AtomicInteger counts = new AtomicInteger();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private GraphSizeEstimates.Counter counting(long triples) {
        return projectId -> {
            counts.incrementAndGet();
            return triples;
        };
    }

    private static void waitUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void aSizeSavedAtImportIsUsedWithoutCounting() {
        sizes.seed("p", 1_200_000);

        assertEquals(1_200_000, sizes.estimate("p", counting(5)));
        assertEquals(0, counts.get());
    }

    @Test
    void anUnknownSizeIsCountedOnceAndRemembered() {
        assertEquals(42, sizes.estimate("p", counting(42)));
        assertEquals(42, sizes.estimate("p", counting(99)));
        assertEquals(1, counts.get());
    }

    @Test
    void aSlowCountGivesUnknownAndIsNotRetriedForFiveMinutes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GraphSizeEstimates.Counter slow = projectId -> {
            counts.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return 3_000_000L;
        };

        assertEquals(-1, sizes.estimate("p", slow));
        assertEquals(-1, sizes.estimate("p", slow));
        assertEquals(1, counts.get());

        release.countDown();
        waitUntil(() -> sizes.known("p").isPresent());
        assertEquals(3_000_000, sizes.estimate("p", slow));
        assertEquals(1, counts.get());
    }

    @Test
    void aFailedCountIsRetriedAfterTheUnknownPeriod() {
        GraphSizeEstimates.Counter broken = projectId -> {
            counts.incrementAndGet();
            throw new IllegalStateException("fuseki down");
        };
        assertEquals(-1, sizes.estimate("p", broken));
        assertEquals(-1, sizes.estimate("p", broken));
        assertEquals(1, counts.get());

        now.addAndGet(GraphSizeEstimates.UNKNOWN_TTL_MILLIS);
        assertEquals(7, sizes.estimate("p", counting(7)));
    }

    @Test
    void anEditKeepsTheSizeAndRecountsInTheBackgroundLater() throws Exception {
        sizes.seed("p", 100);
        sizes.noteWrite("p");

        assertEquals(100, sizes.estimate("p", counting(101)));
        assertEquals(0, counts.get());

        now.addAndGet(GraphSizeEstimates.REFRESH_AFTER_WRITE_MILLIS);
        assertEquals(100, sizes.estimate("p", counting(101)));
        waitUntil(() -> sizes.known("p").orElse(0) == 101);
        assertEquals(101, sizes.estimate("p", counting(500)));
        assertEquals(1, counts.get());
    }

    @Test
    void anImportOrClearForgetsTheSize() {
        sizes.seed("p", 100);
        sizes.reset("p");

        assertEquals(OptionalLong.empty(), sizes.known("p"));
        assertEquals(9, sizes.estimate("p", counting(9)));
    }

    @Test
    void manyCallersAtOnceShareOneCount() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GraphSizeEstimates patient = new GraphSizeEstimates(pool, now::get, 5_000);
        GraphSizeEstimates.Counter slow = projectId -> {
            counts.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return 77L;
        };
        ExecutorService callers = Executors.newFixedThreadPool(10);
        try {
            List<Future<Long>> answers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                answers.add(callers.submit(() -> patient.estimate("p", slow)));
            }
            Thread.sleep(100);
            release.countDown();
            for (Future<Long> answer : answers) {
                assertEquals(77L, answer.get(5, TimeUnit.SECONDS));
            }
        } finally {
            callers.shutdownNow();
        }
        assertEquals(1, counts.get());
    }

    @Test
    void whileBothWorkersAreBusyAQuickEstimateAnswersAtOnceAndAnExactSizeIsCountedOnTheSpot() throws Exception {
        GraphSizeEstimates shared = new GraphSizeEstimates();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        GraphSizeEstimates.Counter slow = projectId -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 77;
        };

        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Long> first = callers.submit(() -> shared.exact("first", slow));
            Future<Long> second = callers.submit(() -> shared.exact("second", slow));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2),
                        () -> assertEquals(-1, shared.estimate("other", counting(5))));
                assertTimeoutPreemptively(Duration.ofSeconds(2),
                        () -> assertEquals(4, shared.exact("small", counting(4))));
                assertFalse(shared.looksLarge("small", 10));
                assertEquals(1, counts.get());
            } finally {
                release.countDown();
            }
            assertEquals(77L, first.get(5, TimeUnit.SECONDS));
            assertEquals(77L, second.get(5, TimeUnit.SECONDS));
            waitUntil(() -> shared.estimate("small", counting(4)) == 4);
            assertEquals(4, shared.known("small").orElseThrow());
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void anExactSizeWaitsForTheCountInsteadOfGuessing() {
        sizes.seed("p", 100);
        sizes.noteWrite("p");

        assertEquals(104, sizes.exact("p", counting(104)));
        assertEquals(104, sizes.exact("p", counting(999)));
        assertEquals(1, counts.get());
    }

    @Test
    void anEditDuringACountLeavesTheResultMarkedForRefresh() throws Exception {
        CountDownLatch counting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GraphSizeEstimates patient = new GraphSizeEstimates(pool, now::get, 5_000);
        Future<?> caller = pool.submit(() -> patient.estimate("p", projectId -> {
            counting.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 50L;
        }));
        assertTrue(counting.await(5, TimeUnit.SECONDS));
        now.addAndGet(10);
        patient.noteWrite("p");
        release.countDown();
        caller.get(5, TimeUnit.SECONDS);

        assertEquals(51, patient.exact("p", counting(51)));
        assertEquals(1, counts.get());
    }

    @Test
    void aFinishedCountIsRememberedBeforeItsCallerSeesTheAnswer() {
        for (int i = 0; i < 300; i++) {
            String project = "p" + i;
            assertEquals(42, sizes.estimate(project, counting(42)));
            assertEquals(42, sizes.estimate(project, counting(99)));
        }
        assertEquals(300, counts.get());
    }

    @Test
    void aCountStillRunningWhenTheGraphIsReplacedIsThrownAway() throws Exception {
        CountDownLatch counting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GraphSizeEstimates patient = new GraphSizeEstimates(pool, now::get, 5_000);
        Future<?> caller = pool.submit(() -> patient.estimate("p", projectId -> {
            counting.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 900L;
        }));
        assertTrue(counting.await(5, TimeUnit.SECONDS));
        patient.reset("p");
        release.countDown();
        caller.get(5, TimeUnit.SECONDS);

        assertEquals(OptionalLong.empty(), patient.known("p"));
        assertEquals(12, patient.estimate("p", counting(12)));
    }

    @Test
    void savedMetadataIsReadFromEitherPlaceImportsUse() {
        assertEquals(10, GraphSizeEstimates.storedTriples(Map.of("tripleCount", 10)));
        assertEquals(20, GraphSizeEstimates.storedTriples(Map.of("counts", Map.of("triples", 20))));
        assertEquals(-1, GraphSizeEstimates.storedTriples(Map.of("counts", Map.of("classes", 5))));
        assertEquals(-1, GraphSizeEstimates.storedTriples(null));
    }

    @Test
    void aSizeTakenFromSavedMetadataIsAnEstimateNotAnExactCount() {
        sizes.seed("p", 100);

        assertEquals(100, sizes.estimate("p", counting(120)));
        assertEquals(0, counts.get());
        assertEquals(120, sizes.exact("p", counting(120)));
        assertEquals(1, counts.get());
        assertEquals(120, sizes.exact("p", counting(999)));
        assertEquals(1, counts.get());
    }
}
