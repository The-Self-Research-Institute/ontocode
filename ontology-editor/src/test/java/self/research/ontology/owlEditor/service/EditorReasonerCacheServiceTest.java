package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EditorReasonerCacheServiceTest {

    private EditorReasonerCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new EditorReasonerCacheService(mock(ReasonerService.class));
        ReflectionTestUtils.setField(cache, "maxHierarchies", 16);
        ReflectionTestUtils.setField(cache, "heapComfortUsedRatio", 1.1);
        ReflectionTestUtils.setField(cache, "heapPressureUsedRatio", 1.1);
    }

    private void put(String key) {
        cache.putHierarchy(key, new EditorReasonerCacheService.HierarchyCacheEntry(List.of(), "HermiT"));
    }

    private boolean has(String key) {
        return cache.getHierarchy(key).isPresent();
    }

    @Test
    void invalidatingOneUsersDraftKeepsPublishedAndOtherUsersEntries() {
        String published = "proj-1-HERMIT";
        String mine = EditorReasonerCacheService.draftHierarchyKey("proj-1", ReasonerType.HERMIT, "user-1", "3.0");
        String similarUser = EditorReasonerCacheService.draftHierarchyKey("proj-1", ReasonerType.HERMIT, "user-10", "3.0");
        String otherProject = EditorReasonerCacheService.draftHierarchyKey("proj-2", ReasonerType.HERMIT, "user-1", "3.0");
        put(published);
        put(mine);
        put(similarUser);
        put(otherProject);

        cache.invalidateDraft("proj-1", "user-1");

        assertFalse(has(mine));
        assertTrue(has(published));
        assertTrue(has(similarUser));
        assertTrue(has(otherProject));
    }

    @Test
    void invalidatingDraftsWithoutAUserKeepsPublishedEntries() {
        String published = "proj-1-HERMIT";
        String first = EditorReasonerCacheService.draftHierarchyKey("proj-1", ReasonerType.HERMIT, "user-1", "1.0");
        String second = EditorReasonerCacheService.draftHierarchyKey("proj-1", ReasonerType.ELK, "user-2", "4.2");
        put(published);
        put(first);
        put(second);

        cache.invalidateDraft("proj-1", null);

        assertFalse(has(first));
        assertFalse(has(second));
        assertTrue(has(published));
    }

    @Test
    void hierarchyKeysDifferByRulesAndStayUnchangedWithoutRules() {
        assertEquals("proj-1-HERMIT", EditorReasonerCacheService.hierarchyKey("proj-1", ReasonerType.HERMIT, "none"));
        assertNotEquals(EditorReasonerCacheService.hierarchyKey("proj-1", ReasonerType.HERMIT, "aaa"),
                EditorReasonerCacheService.hierarchyKey("proj-1", ReasonerType.HERMIT, "bbb"));
        assertEquals(EditorReasonerCacheService.draftHierarchyKey("proj-1", ReasonerType.HERMIT, "u", "1.0"),
                EditorReasonerCacheService.draftHierarchyKey("proj-1", ReasonerType.HERMIT, "u", "1.0", "none"));
    }

    @Test
    void snapshotComputedWithDifferentRulesIsAMissAndIsEvicted() {
        String key = EditorReasonerCacheService.snapshotKey("proj-1", "HERMIT", 3L);
        cache.putSnapshot(key, Map.of("a", 1), "aaa");

        assertTrue(cache.getSnapshot(key, () -> "aaa").isPresent());
        assertFalse(cache.getSnapshot(key, () -> "bbb").isPresent());
        assertFalse(cache.getSnapshot(key, () -> "aaa").isPresent());
    }

    @Test
    void snapshotWithoutRulesIsAHitWhenStillWithoutRules() {
        String key = EditorReasonerCacheService.snapshotKey("proj-1", "HERMIT", 3L);
        cache.putSnapshot(key, Map.of("a", 1), "none");

        assertTrue(cache.getSnapshot(key, () -> "none").isPresent());
        assertFalse(cache.getSnapshot(key, () -> "aaa").isPresent());
    }
}
