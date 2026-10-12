package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.repository.HierarchySnapshotRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchyIndexServiceBuildQueueTest {

    private final List<Runnable> queued = new ArrayList<>();
    private HierarchySnapshotBuildService buildService;
    private HierarchyIndexService service;

    @BeforeEach
    void setUp() {
        buildService = mock(HierarchySnapshotBuildService.class);
        when(buildService.isEnabled()).thenReturn(true);
        service = new HierarchyIndexService(mock(HierarchySnapshotRepository.class), buildService, queued::add);
        ReflectionTestUtils.setField(service, "snapshotEnabled", true);
    }

    @Test
    void requestsBeforeTheBuildStartsShareOneBuild() {
        CompletableFuture<Void> first = service.scheduleBuild("p1");
        CompletableFuture<Void> second = service.scheduleBuild("p1");

        assertSame(first, second);
        assertEquals(1, queued.size());
        queued.get(0).run();
        verify(buildService, times(1)).buildAndStore(eq("p1"), anyString());
        assertTrue(first.isDone() && !first.isCompletedExceptionally());
    }

    @Test
    void aRequestAfterTheBuildStartedQueuesAnotherBuild() {
        service.scheduleBuild("p1");
        queued.get(0).run();

        service.scheduleBuild("p1");

        assertEquals(2, queued.size());
    }

    @Test
    void differentProjectsBuildIndependently() {
        service.scheduleBuild("p1");
        service.scheduleBuild("p2");

        assertEquals(2, queued.size());
    }

    @Test
    void aFailedBuildCompletesItsFutureExceptionallyAndDoesNotBlockTheNextOne() {
        doThrow(new IllegalStateException("boom")).when(buildService).buildAndStore(eq("p1"), anyString());
        CompletableFuture<Void> failed = service.scheduleBuild("p1");
        queued.get(0).run();

        assertTrue(failed.isCompletedExceptionally());
        service.scheduleBuild("p1");
        assertEquals(2, queued.size());
    }

    @Test
    void aRejectedSubmissionIsNotLeftQueued() {
        HierarchyIndexService rejecting = new HierarchyIndexService(mock(HierarchySnapshotRepository.class), buildService,
                task -> {
                    throw new RejectedExecutionException("full");
                });
        ReflectionTestUtils.setField(rejecting, "snapshotEnabled", true);

        assertThrows(RejectedExecutionException.class, () -> rejecting.scheduleBuild("p1"));
        assertThrows(RejectedExecutionException.class, () -> rejecting.scheduleBuild("p1"));
    }
}
