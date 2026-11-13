package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.repository.JdbcProjectRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequireDraftFlagPersistenceTest {

    private static final String FLAG = "requireDraftForMembers";

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcProjectRepository repository = new JdbcProjectRepository(jdbc);
    private final ProjectMetadataService service = new ProjectMetadataService(repository, null);

    @BeforeEach
    void clean() {
        jdbc.update("delete from ontology_file");
    }

    private void fileRow(String id, String parent) {
        repository.upsertProjectMetadata(id, "UPLOADED", null, id + ".owl", null, null, null, "ws", parent, Instant.now());
    }

    private Object storedFlag(String id) {
        Map<String, Object> meta = repository.findById(id).orElseThrow().getMetadata();
        return meta == null ? null : meta.get(FLAG);
    }

    @Test
    void flagSurvivesAWriteWhoseMapLacksIt() {
        fileRow("p1--f1", "p1");
        service.setRequireDraftForMembers("p1--f1", true);

        service.writeMeta("p1--f1", new HashMap<>(Map.of("cache", "x")));

        assertEquals(true, storedFlag("p1--f1"));
        assertEquals("x", repository.findById("p1--f1").orElseThrow().getMetadata().get("cache"));
    }

    @Test
    void flagSurvivesAWriteWhoseMapCarriesFalse() {
        fileRow("p1--f1", "p1");
        service.setRequireDraftForMembers("p1--f1", true);

        service.writeMeta("p1--f1", new HashMap<>(Map.of(FLAG, false)));

        assertEquals(true, storedFlag("p1--f1"));
    }

    @Test
    void aGenericWriteCannotTurnTheFlagOn() {
        fileRow("p1--f1", "p1");
        service.setRequireDraftForMembers("p1--f1", false);

        service.writeMeta("p1--f1", new HashMap<>(Map.of(FLAG, true)));
        repository.upsertMetadata("fresh--f", Map.of(FLAG, true), "UPLOADED", Instant.now());

        assertEquals(false, storedFlag("p1--f1"));
        assertNull(storedFlag("fresh--f"));
        assertFalse(service.isRequireDraftForMembers("fresh--f"));
    }

    @Test
    void setterCreatesMetadataWhenNullAndWorksForAMissingRow() {
        fileRow("p1--f1", "p1");
        service.setRequireDraftForMembers("p1--f1", true);
        assertEquals(true, storedFlag("p1--f1"));

        service.setRequireDraftForMembers("solo", true);
        assertTrue(service.isRequireDraftForMembers("solo"));
    }

    @Test
    void setterUpdatesEveryFileOfTheProjectAndNoOtherProject() {
        fileRow("p1--f1", "p1");
        fileRow("p1--f2", "p1");
        fileRow("p2--f1", "p2");

        service.setRequireDraftForMembers("p1--f1", true);

        assertEquals(true, storedFlag("p1--f1"));
        assertEquals(true, storedFlag("p1--f2"));
        assertNull(storedFlag("p2--f1"));

        service.setRequireDraftForMembers("p1", false);
        assertEquals(false, storedFlag("p1--f1"));
        assertEquals(false, storedFlag("p1--f2"));
    }

    @Test
    void getterIsTrueWhenAnyFileOfTheProjectIsFlagged() {
        fileRow("p1--f1", "p1");
        fileRow("p1--f2", "p1");
        assertFalse(service.isRequireDraftForMembers("p1--f2"));

        jdbc.update("update ontology_file set metadata = '{\"requireDraftForMembers\": true}'::jsonb where id = 'p1--f1'");

        assertTrue(service.isRequireDraftForMembers("p1--f2"));
        assertTrue(service.isRequireDraftForMembers("p1"));
        assertFalse(service.isRequireDraftForMembers("p2--f1"));
        assertFalse(service.isRequireDraftForMembers(null));
    }

    @Test
    void staleWritersNeverDropTheLastToggle() throws Exception {
        fileRow("p1--f1", "p1");
        service.setRequireDraftForMembers("p1--f1", true);
        Map<String, Object> staleWithout = new HashMap<>(Map.of("cache", "stale"));
        Map<String, Object> staleWithFalse = new HashMap<>(Map.of("cache", "stale", FLAG, false));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> toggler = pool.submit(() -> {
                start.await();
                boolean last = true;
                for (int i = 0; i < 200; i++) {
                    last = i % 2 == 0;
                    service.setRequireDraftForMembers("p1--f1", last);
                }
                return last;
            });
            Future<?> writer = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    service.writeMeta("p1--f1", i % 2 == 0 ? staleWithout : staleWithFalse);
                }
                return null;
            });
            start.countDown();
            boolean last = toggler.get(60, TimeUnit.SECONDS);
            writer.get(60, TimeUnit.SECONDS);
            assertEquals(last, storedFlag("p1--f1"));
            assertEquals(last, service.isRequireDraftForMembers("p1--f1"));
        } finally {
            pool.shutdownNow();
        }
    }
}
