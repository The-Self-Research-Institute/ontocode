package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineCacheTest {

    private final List<String> disposed = new ArrayList<>();
    private final EngineCache<String> cache = new EngineCache<>(2, disposed::add);

    @Test
    void theLeastRecentlyUsedEngineIsEvictedAndDisposedWhenNobodyUsesIt() {
        cache.acquire("a", () -> "A").close();
        cache.acquire("b", () -> "B").close();
        cache.acquire("a", () -> "A2").close();
        cache.acquire("c", () -> "C").close();

        assertEquals(2, cache.size());
        assertEquals(List.of("B"), disposed);
        assertTrue(cache.keys().containsAll(List.of("a", "c")));
    }

    @Test
    void anEvictedEngineInUseIsDisposedOnlyWhenTheLeaseIsReleased() {
        EngineCache<String>.Lease held = cache.acquire("a", () -> "A");
        cache.acquire("b", () -> "B").close();
        cache.acquire("c", () -> "C").close();

        assertEquals(2, cache.size());
        assertFalse(disposed.contains("A"));
        assertEquals("A", held.engine());

        held.close();
        held.close();

        assertEquals(List.of("A"), disposed);
    }

    @Test
    void theStaleSweepSkipsEnginesThatAreLeased() throws Exception {
        EngineCache<String>.Lease held = cache.acquire("a", () -> "A");
        cache.acquire("b", () -> "B").close();
        Thread.sleep(5);

        int removed = cache.evictOlderThan(System.currentTimeMillis() + 1000);

        assertEquals(1, removed);
        assertEquals(List.of("B"), disposed);
        assertEquals(List.of("a"), cache.keys());
        held.close();
        assertEquals(1, cache.evictOlderThan(System.currentTimeMillis() + 1000));
        assertEquals(List.of("B", "A"), disposed);
    }

    @Test
    void shrinkingTheMaximumTrimsTheCache() {
        cache.acquire("a", () -> "A").close();
        cache.acquire("b", () -> "B").close();

        cache.setMaxSize(1);

        assertEquals(1, cache.size());
        assertEquals(List.of("A"), disposed);
    }
}
