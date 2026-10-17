package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyCacheConcurrencyTest {

    @Test
    void readingACachedProjectWhileAnotherJobEvictsByPutMustNotDeadlock() throws Exception {
        OntologyCache cache = new OntologyCache();
        ReflectionTestUtils.setField(cache, "maxProjects", 1);
        OWLOntologyManager firstManager = OWLManager.createOWLOntologyManager();
        cache.put("P1", 1, 10, firstManager, firstManager.createOntology());

        AtomicBoolean stop = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                cache.getAndBeginUse("P1", 1, 10).ifPresent(cache::endUse);
            }
        }, "repro-reader-getAndBeginUse");
        Thread writer = new Thread(() -> {
            long i = 0;
            while (!stop.get()) {
                try {
                    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                    OWLOntology ontology = manager.createOntology();
                    cache.put((i++ % 2 == 0) ? "P2" : "P1", 1, 10, manager, ontology);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, "repro-writer-put-evicts");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && deadlocked == null) {
            deadlocked = mx.findDeadlockedThreads();
            if (deadlocked == null) {
                Thread.sleep(25);
            }
        }
        stop.set(true);

        StringBuilder detail = new StringBuilder();
        if (deadlocked != null) {
            for (ThreadInfo info : mx.getThreadInfo(deadlocked, true, true)) {
                detail.append("\n  ").append(info.getThreadName())
                      .append(" waits for ").append(info.getLockName())
                      .append(" held by ").append(info.getLockOwnerName());
            }
        }
        assertNull(deadlocked, "OntologyCache deadlock between getAndBeginUse and put:" + detail);
    }

    @Test
    void putAndBeginUseKeepsTheOntologyLoadedUntilEndUseEvenIfEvictedRightAway() throws Exception {
        OntologyCache cache = new OntologyCache();
        ReflectionTestUtils.setField(cache, "maxProjects", 1);
        OWLOntologyManager firstManager = OWLManager.createOWLOntologyManager();
        OWLOntology first = firstManager.createOntology();

        cache.putAndBeginUse("P1", 1, 10, firstManager, first);

        OWLOntologyManager secondManager = OWLManager.createOWLOntologyManager();
        cache.put("P2", 1, 10, secondManager, secondManager.createOntology());

        assertTrue(cache.get("P1", 1, 10).isEmpty());
        assertTrue(firstManager.contains(first), "in-use ontology must not be removed while a job is reading it");

        cache.endUse(first);

        assertFalse(firstManager.contains(first), "deferred eviction must run once the last user is done");
    }

    @Test
    void lruEvictionRemovesAnUnusedOntologyImmediately() throws Exception {
        OntologyCache cache = new OntologyCache();
        ReflectionTestUtils.setField(cache, "maxProjects", 1);
        OWLOntologyManager firstManager = OWLManager.createOWLOntologyManager();
        OWLOntology first = firstManager.createOntology();
        cache.put("P1", 1, 10, firstManager, first);

        OWLOntologyManager secondManager = OWLManager.createOWLOntologyManager();
        OWLOntology second = secondManager.createOntology();
        cache.put("P2", 1, 10, secondManager, second);

        assertFalse(firstManager.contains(first));
        assertTrue(cache.get("P2", 1, 10).isPresent());
    }
}
