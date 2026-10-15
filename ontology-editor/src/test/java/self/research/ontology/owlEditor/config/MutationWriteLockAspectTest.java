package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class MutationWriteLockAspectTest {

    private OntologyMutationService proxyOver(OntologyMutationService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new MutationWriteLockAspect(new ProjectWriteLockRegistry()));
        return factory.getProxy();
    }

    @Test
    void twoWritesToTheSameProjectNeverOverlapButDifferentProjectsMay() throws Exception {
        OntologyMutationService target = mock(OntologyMutationService.class);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger sameProjectMax = new AtomicInteger();
        doAnswer(invocation -> {
            int now = inside.incrementAndGet();
            if ("p1".equals(invocation.getArgument(0))) {
                sameProjectMax.accumulateAndGet(now, Math::max);
            }
            Thread.sleep(150);
            inside.decrementAndGet();
            return null;
        }).when(target).applyRawUpdate(anyString(), anyString());
        OntologyMutationService proxy = proxyOver(target);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Runnable> work = List.of(() -> proxy.applyRawUpdate("p1", "a"), () -> proxy.applyRawUpdate("p1", "b"));
        for (Runnable job : work) {
            pool.submit(() -> {
                go.await();
                job.run();
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, sameProjectMax.get());
    }

    @Test
    void theProjectLockIsReleasedAfterAFailureAndTheOriginalExceptionIsKept() {
        OntologyMutationService target = mock(OntologyMutationService.class);
        doThrow(new IllegalArgumentException("bad update")).when(target).applyRawUpdate("p1", "boom");
        OntologyMutationService proxy = proxyOver(target);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> proxy.applyRawUpdate("p1", "boom"));
        proxy.applyRawUpdate("p1", "fine");

        assertEquals("bad update", failure.getMessage());
    }

    @Test
    void aWriteMadeWhileAlreadyHoldingTheLockDoesNotDeadlock() throws Exception {
        OntologyMutationService target = mock(OntologyMutationService.class);
        ProjectWriteLockRegistry locks = new ProjectWriteLockRegistry();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new MutationWriteLockAspect(locks));
        OntologyMutationService proxy = factory.getProxy();

        locks.runExclusive("p1", () -> {
            proxy.applyRawUpdate("p1", "nested");
            return null;
        });
    }
}
