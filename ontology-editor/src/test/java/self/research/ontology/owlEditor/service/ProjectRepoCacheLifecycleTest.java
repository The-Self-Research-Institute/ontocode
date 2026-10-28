package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.impl.IteratingGraphQueryResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRepoCacheLifecycleTest {

    static final ValueFactory VF = SimpleValueFactory.getInstance();

    static ProjectRepoCache newCache() {
        ProjectRepoCache cache = new ProjectRepoCache();
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "maxProjects", 3);
        ReflectionTestUtils.setField(cache, "maxTriples", 1_000_000L);
        ReflectionTestUtils.setField(cache, "noCacheThreshold", 1_000_000L);
        return cache;
    }

    static ProjectRepoCache.Loader loader() {
        return new ProjectRepoCache.Loader() {
            @Override
            public GraphQueryResult streamTriples(String projectId) {
                Statement st = VF.createStatement(VF.createIRI("http://e/s"), VF.createIRI("http://e/p"),
                        VF.createLiteral("v"));
                return new IteratingGraphQueryResult(Map.of(), List.of(st).iterator());
            }

            @Override
            public String graphUri(String projectId) {
                return "http://e/g/" + projectId;
            }

            @Override
            public long estimateTripleCount(String projectId) {
                return 1;
            }
        };
    }

    static ProjectRepoCache.Lease warm(ProjectRepoCache cache, String id) throws Exception {
        cache.borrow(id, loader());
        for (int i = 0; i < 200 && cache.getSize() == 0; i++) {
            Thread.sleep(25);
        }
        return cache.borrow(id, loader());
    }

    @Test
    void evictWhileAReaderIsBorrowingKeepsTheRepositoryOpenUntilRelease() throws Exception {
        ProjectRepoCache cache = newCache();
        ProjectRepoCache.Lease lease = warm(cache, "p");
        assertNotNull(lease);
        var repo = lease.repository();
        try (var conn = repo.getConnection()) {
            cache.evict("p");
            assertTrue(repo.isInitialized());
            assertTrue(conn.size() >= 1);
        }
        assertTrue(repo.isInitialized());
        lease.close();
        assertFalse(repo.isInitialized());
        lease.close();
        assertNull(cache.borrow("p", failingLoader()));
    }

    @Test
    void evictWithNoReaderShutsDownAtOnce() throws Exception {
        ProjectRepoCache cache = newCache();
        ProjectRepoCache.Lease lease = warm(cache, "p");
        var repo = lease.repository();
        lease.close();
        assertTrue(repo.isInitialized());
        cache.evict("p");
        assertFalse(repo.isInitialized());
    }

    @Test
    void manyReadersAllFinishAfterEvictAndTheLastOneShutsItDown() throws Exception {
        ProjectRepoCache cache = newCache();
        ProjectRepoCache.Lease a = warm(cache, "p");
        ProjectRepoCache.Lease b = cache.borrow("p", loader());
        var repo = a.repository();
        cache.evict("p");
        a.close();
        assertTrue(repo.isInitialized());
        b.close();
        assertFalse(repo.isInitialized());
    }

    static ProjectRepoCache.Loader failingLoader() {
        return new ProjectRepoCache.Loader() {
            @Override
            public GraphQueryResult streamTriples(String projectId) throws Exception {
                throw new IllegalStateException("not loaded in this test");
            }

            @Override
            public String graphUri(String projectId) {
                return "http://e/g/" + projectId;
            }
        };
    }
}
