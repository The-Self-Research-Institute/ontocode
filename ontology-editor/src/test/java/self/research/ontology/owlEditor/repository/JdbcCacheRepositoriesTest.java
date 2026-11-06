package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.ExpiringTable;
import self.research.ontology.common.db.ExpiryPurger;
import self.research.ontology.common.db.ProjectScopedTables;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.config.CacheExpiryConfiguration;
import self.research.ontology.owlEditor.document.ClassDetailDocument;
import self.research.ontology.owlEditor.document.EntityUsageDocument;
import self.research.ontology.owlEditor.document.TopLevelClassCacheDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCacheRepositoriesTest {

    private static final String IRI = "http://example.org/o.1:x/y#Thing";

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcClassDetailRepository classDetails = new JdbcClassDetailRepository(jdbc);
    private final JdbcEntityUsageRepository usages = new JdbcEntityUsageRepository(jdbc);
    private final JdbcTopLevelClassCacheRepository topLevel = new JdbcTopLevelClassCacheRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from class_detail");
        jdbc.update("delete from entity_usage");
        jdbc.update("delete from top_level_class_cache");
    }

    private static Map<String, Object> details(String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", IRI);
        map.put("label", label);
        map.put("annotations", Map.of("http://www.w3.org/2000/01/rdf-schema#label", label));
        return map;
    }

    private static OntologyDto.TreeNode node(String id) {
        OntologyDto.TreeNode n = new OntologyDto.TreeNode();
        n.setId(id);
        n.setLabel("L " + id);
        return n;
    }

    @Test
    void aClassDetailRoundTripsAndFullDetailsOverwriteAPartialOne() {
        classDetails.save(new ClassDetailDocument("p1", IRI, details("first"), true));
        ClassDetailDocument partial = classDetails.findByProjectIdAndClassIri("p1", IRI).orElseThrow();
        assertTrue(partial.isPartial());
        assertEquals("first", partial.getDetails().get("label"));
        assertEquals(IRI, partial.getClassIri());
        assertTrue(partial.getBuiltAt() > 0);

        classDetails.save(new ClassDetailDocument("p1", IRI, details("second"), false));

        ClassDetailDocument full = classDetails.findByProjectIdAndClassIri("p1", IRI).orElseThrow();
        assertFalse(full.isPartial());
        assertEquals("second", full.getDetails().get("label"));
        assertEquals(1, jdbc.queryForObject("select count(*) from class_detail", Integer.class));
        assertTrue(classDetails.findByProjectIdAndClassIri("p2", IRI).isEmpty());
    }

    @Test
    void theSameClassInTwoProjectsIsTwoRowsButTheSamePairTwiceIsRefused() {
        classDetails.save(new ClassDetailDocument("p1", IRI, details("a"), false));
        classDetails.save(new ClassDetailDocument("p2", IRI, details("b"), false));
        assertEquals(2, jdbc.queryForObject("select count(*) from class_detail", Integer.class));

        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "insert into class_detail (project_id, class_iri) values ('p1', ?)", IRI));
    }

    @Test
    void saveIfAbsentKeepsTheExistingRowAndReportsWhetherItInserted() {
        assertTrue(classDetails.saveIfAbsent(new ClassDetailDocument("p1", IRI, details("first"), true)));
        assertFalse(classDetails.saveIfAbsent(new ClassDetailDocument("p1", IRI, details("second"), true)));

        assertEquals("first", classDetails.findByProjectIdAndClassIri("p1", IRI).orElseThrow()
                .getDetails().get("label"));
    }

    @Test
    void manyThreadsSavingTheSameClassIfAbsentHaveExactlyOneWinner() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String label = "t" + i;
            futures.add(pool.submit(() -> {
                start.await();
                if (classDetails.saveIfAbsent(new ClassDetailDocument("p1", IRI, details(label), true))) {
                    winners.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, winners.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from class_detail", Integer.class));
    }

    @Test
    void saveAllIfAbsentInsertsOnlyTheMissingOnesAcrossChunks() {
        classDetails.save(new ClassDetailDocument("p1", "http://e.org/c0", details("keep"), false));
        List<ClassDetailDocument> docs = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            docs.add(new ClassDetailDocument("p1", "http://e.org/c" + i, details("n" + i), true));
        }

        int inserted = classDetails.saveAllIfAbsent(docs);

        assertEquals(2499, inserted);
        assertEquals(2500, jdbc.queryForObject("select count(*) from class_detail", Integer.class));
        assertEquals("keep", classDetails.findByProjectIdAndClassIri("p1", "http://e.org/c0").orElseThrow()
                .getDetails().get("label"));
    }

    @Test
    void classDetailsAreInvalidatedByIriListAndByProject() {
        classDetails.save(new ClassDetailDocument("p1", "http://e.org/a", details("a"), false));
        classDetails.save(new ClassDetailDocument("p1", "http://e.org/b", details("b"), false));
        classDetails.save(new ClassDetailDocument("p1", "http://e.org/c", details("c"), false));
        classDetails.save(new ClassDetailDocument("p2", "http://e.org/a", details("a"), false));

        classDetails.deleteByProjectIdAndClassIriIn("p1", List.of("http://e.org/a", "http://e.org/b"));
        classDetails.deleteByProjectIdAndClassIriIn("p1", List.of());

        assertTrue(classDetails.findByProjectIdAndClassIri("p1", "http://e.org/a").isEmpty());
        assertTrue(classDetails.findByProjectIdAndClassIri("p1", "http://e.org/c").isPresent());
        assertTrue(classDetails.findByProjectIdAndClassIri("p2", "http://e.org/a").isPresent());

        classDetails.deleteByProjectId("p1");
        assertEquals(1, jdbc.queryForObject("select count(*) from class_detail", Integer.class));
    }

    @Test
    void entityUsageRoundTripsAndASecondSaveReplacesTheRow() {
        List<Map<String, String>> list = List.of(
                Map.of("type", "subclass", "subject", "http://e.org/k", "subjectLabel", "K", "context", "SubClassOf this"));
        usages.saveAll(List.of(new EntityUsageDocument("p1", IRI, 4, list)));

        EntityUsageDocument read = usages.findByProjectIdAndEntityIri("p1", IRI).orElseThrow();
        assertEquals(4, read.getRevision());
        assertEquals(list, read.getUsages());
        assertTrue(read.getBuiltAt() > 0);

        usages.saveAll(List.of(new EntityUsageDocument("p1", IRI, 5, List.of())));

        assertEquals(5, usages.findByProjectIdAndEntityIri("p1", IRI).orElseThrow().getRevision());
        assertTrue(usages.findByProjectIdAndEntityIri("p1", IRI).orElseThrow().getUsages().isEmpty());
        assertEquals(1, jdbc.queryForObject("select count(*) from entity_usage", Integer.class));
    }

    @Test
    void entityUsageBulkSaveWritesEveryRowAcrossChunksAndRewritesOnRebuild() {
        List<EntityUsageDocument> docs = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            docs.add(new EntityUsageDocument("p1", "http://e.org/e" + i, 1, List.of(Map.of("type", "x"))));
        }

        usages.saveAll(docs);
        usages.saveAll(docs);

        assertEquals(2500, jdbc.queryForObject("select count(*) from entity_usage", Integer.class));
    }

    @Test
    void entityUsageIsInvalidatedByIriListAndByProject() {
        usages.saveAll(List.of(
                new EntityUsageDocument("p1", "http://e.org/a", 1, List.of()),
                new EntityUsageDocument("p1", "http://e.org/b", 1, List.of()),
                new EntityUsageDocument("p2", "http://e.org/a", 1, List.of())));

        usages.deleteByProjectIdAndEntityIriIn("p1", List.of("http://e.org/a"));

        assertTrue(usages.findByProjectIdAndEntityIri("p1", "http://e.org/a").isEmpty());
        assertTrue(usages.findByProjectIdAndEntityIri("p1", "http://e.org/b").isPresent());
        assertTrue(usages.findByProjectIdAndEntityIri("p2", "http://e.org/a").isPresent());

        usages.deleteByProjectId("p1");
        assertEquals(1, jdbc.queryForObject("select count(*) from entity_usage", Integer.class));
    }

    @Test
    void theTopLevelCacheRoundTripsWithItsLimitAndExpiry() {
        topLevel.save(new TopLevelClassCacheDoc("p1", List.of(node("http://e.org/a"), node("http://e.org/b")), 50));

        TopLevelClassCacheDoc read = topLevel.findById("p1").orElseThrow();
        assertEquals(2, read.getNodes().size());
        assertEquals("http://e.org/b", read.getNodes().get(1).getId());
        assertEquals(50, read.getComputedWithLimit());
        assertTrue(read.coversLimit(2));
        assertFalse(read.isStale());
        assertTrue(read.getExpiresAt().getTime() > System.currentTimeMillis());

        topLevel.save(new TopLevelClassCacheDoc("p1", List.of(node("http://e.org/c")), 10));
        assertEquals(1, topLevel.findById("p1").orElseThrow().getNodes().size());
        assertEquals(1, topLevel.count());
    }

    @Test
    void anExpiredTopLevelCacheIsIgnoredAndThePurgerDeletesIt() {
        topLevel.save(new TopLevelClassCacheDoc("p1", List.of(node("http://e.org/a")), 50));
        topLevel.save(new TopLevelClassCacheDoc("p2", List.of(node("http://e.org/a")), 50));
        jdbc.update("update top_level_class_cache set expires_at = now() - interval '1 minute' where project_id = 'p1'");

        assertTrue(topLevel.findById("p1").isEmpty());
        assertTrue(topLevel.findById("p2").isPresent());
        assertEquals(1, topLevel.count());

        int purged = new ExpiryPurger(jdbc, null, 100).purge(ExpiringTable.of("top_level_class_cache"));

        assertEquals(1, purged);
        assertEquals(1, jdbc.queryForObject("select count(*) from top_level_class_cache", Integer.class));
    }

    @Test
    void evictionRemovesOneProjectOrEverything() {
        topLevel.save(new TopLevelClassCacheDoc("p1", List.of(), 50));
        topLevel.save(new TopLevelClassCacheDoc("p2", List.of(), 50));

        topLevel.deleteById("p1");
        assertTrue(topLevel.findById("p1").isEmpty());
        assertEquals(1, topLevel.count());

        topLevel.deleteAll();
        assertEquals(0, topLevel.count());
    }

    @Test
    void theExpiringTablesAreRegisteredAndEveryCacheTableIsPurgedWithItsProject() {
        CacheExpiryConfiguration configuration = new CacheExpiryConfiguration();
        List<String> expiring = List.of(
                configuration.hierarchySnapshotExpiry().table(),
                configuration.topLevelClassCacheExpiry().table());
        assertEquals(List.of("hierarchy_snapshot", "top_level_class_cache"), expiring);
        assertTrue(ProjectScopedTables.names().containsAll(
                List.of("class_detail", "entity_usage", "hierarchy_snapshot", "top_level_class_cache")));

        classDetails.save(new ClassDetailDocument("gone", IRI, details("a"), false));
        usages.saveAll(List.of(new EntityUsageDocument("gone", IRI, 1, List.of())));
        topLevel.save(new TopLevelClassCacheDoc("gone", List.of(), 50));
        jdbc.update("insert into hierarchy_snapshot (project_id, status) values ('gone', 'READY')");

        ProjectScopedTables.purge(jdbc, "gone");

        assertEquals(0, jdbc.queryForObject("select count(*) from class_detail where project_id = 'gone'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from entity_usage where project_id = 'gone'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from top_level_class_cache where project_id = 'gone'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from hierarchy_snapshot where project_id = 'gone'", Integer.class));
    }
}
