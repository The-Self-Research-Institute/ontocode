package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.ProjectDocument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectRepositoryTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcProjectRepository repository = new JdbcProjectRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from ontology_file");
    }

    private ProjectDocument file(String id, String name, String owner, Instant updated) {
        ProjectDocument doc = new ProjectDocument(id, name, name + ".owl");
        doc.setOwnerEmail(owner);
        doc.setUpdatedAt(updated);
        return doc;
    }

    @Test
    void aSavedFileComesBackWithEveryField() {
        ProjectDocument doc = file("proj-1--f1", "Pizza", "a@x.org", Instant.parse("2026-01-01T00:00:00Z"));
        doc.setOwnerId("u1");
        doc.setProjectId("proj-1");
        doc.setWorkspaceId("ws-1");
        doc.setGridfsFileId("65f0000000000000000000a1");
        doc.setStatusMessage("ok");
        doc.setMetadata(Map.of("prefixes", Map.of("ex:", "http://x.org/a.b#"), "imports", List.of("http://x.org/base")));
        doc.setMutationVersion(4L);
        doc.setMainGraphRevision(9L);

        repository.save(doc);
        ProjectDocument read = repository.findById("proj-1--f1").orElseThrow();

        assertEquals("Pizza", read.getName());
        assertEquals("Pizza.owl", read.getFilename());
        assertEquals("a@x.org", read.getOwnerEmail());
        assertEquals("u1", read.getOwnerId());
        assertEquals("proj-1", read.getProjectId());
        assertEquals("ws-1", read.getWorkspaceId());
        assertEquals("65f0000000000000000000a1", read.getGridfsFileId());
        assertEquals("UPLOADED", read.getStatus());
        assertEquals("ok", read.getStatusMessage());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), read.getUpdatedAt());
        assertEquals(Map.of("ex:", "http://x.org/a.b#"), read.getMetadata().get("prefixes"));
        assertEquals(4L, read.getMutationVersion());
        assertEquals(9L, read.getMainGraphRevision());
        assertTrue(repository.existsById("proj-1--f1"));
        assertFalse(repository.existsById("nope"));
        assertTrue(repository.findById("nope").isEmpty());
    }

    @Test
    void savingAgainReplacesTheRowAndDeleteRemovesIt() {
        repository.save(file("p--f", "A", "a@x.org", Instant.now()));
        ProjectDocument changed = file("p--f", "B", "b@x.org", Instant.now());
        repository.save(changed);

        assertEquals("B", repository.findById("p--f").orElseThrow().getName());
        assertEquals(1, repository.findAll().size());

        repository.deleteById("p--f");
        assertTrue(repository.findById("p--f").isEmpty());
    }

    @Test
    void listingsAreNewestFirstAndFilenameLookupsPickTheNewest() {
        repository.save(file("a", "Same", "o@x.org", Instant.parse("2026-01-01T00:00:00Z")));
        repository.save(file("b", "Same", "o@x.org", Instant.parse("2026-03-01T00:00:00Z")));
        repository.save(file("c", "Other", "o@x.org", Instant.parse("2026-02-01T00:00:00Z")));

        assertEquals(List.of("b", "c", "a"), repository.findAllByOrderByUpdatedAtDesc().stream().map(ProjectDocument::getId).toList());
        assertEquals("b", repository.findFirstByFilenameAndOwnerEmailOrderByUpdatedAtDesc("Same.owl", "o@x.org").orElseThrow().getId());
        assertEquals("b", repository.findFirstByFilenameOrderByUpdatedAtDesc("Same.owl").orElseThrow().getId());
        assertEquals(2, repository.findByFilenameAndOwnerEmail("Same.owl", "o@x.org").size());
        assertTrue(repository.findByFilenameAndOwnerEmail("Same.owl", "z@x.org").isEmpty());
    }

    @Test
    void statusListsMatchOnlyTheRequestedStatuses() {
        ProjectDocument processing = file("a", "A", "o", Instant.now());
        processing.setStatus("PROCESSING");
        ProjectDocument done = file("b", "B", "o", Instant.now());
        done.setStatus("COMPLETED");
        repository.save(processing);
        repository.save(done);

        assertEquals(List.of("a"), repository.findByStatusIn(List.of("PROCESSING", "INDEXING")).stream().map(ProjectDocument::getId).toList());
        assertTrue(repository.findByStatusIn(List.of()).isEmpty());
    }

    @Test
    void importersAreFoundByTheImportedIri() {
        ProjectDocument importer = file("a", "A", "o", Instant.now());
        importer.setMetadata(Map.of("imports", List.of("http://x.org/pizza", "http://x.org/other")));
        ProjectDocument bystander = file("b", "B", "o", Instant.now());
        bystander.setMetadata(Map.of("imports", List.of("http://x.org/else")));
        repository.save(importer);
        repository.save(bystander);
        repository.save(file("c", "C", "o", Instant.now()));

        assertEquals(List.of("a"), repository.findIdsByImportedIri("http://x.org/pizza"));
        assertTrue(repository.findIdsByImportedIri("http://x.org/none").isEmpty());
    }

    @Test
    void statusWriteCreatesTheRowOnceAndKeepsItsCreationTimeAndOwner() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant second = Instant.parse("2026-01-02T00:00:00Z");

        repository.upsertStatus("p--f", "UPLOADED", "queued", "A.owl", first);
        repository.upsertOwnerEmail("p--f", "o@x.org", first);
        repository.upsertStatus("p--f", "COMPLETED", "done", "A.owl", second);

        ProjectDocument read = repository.findById("p--f").orElseThrow();
        assertEquals("p--f", read.getName());
        assertEquals(first, read.getCreatedAt());
        assertEquals(second, read.getUpdatedAt());
        assertEquals("COMPLETED", read.getStatus());
        assertEquals("done", read.getStatusMessage());
        assertEquals("o@x.org", read.getOwnerEmail());
    }

    @Test
    void metadataWriteReplacesTheMetadataAndSetsUploadedOnlyForNewRows() {
        Instant now = Instant.now();
        repository.upsertMetadata("p--f", Map.of("a", 1), "UPLOADED", now);
        repository.upsertStatus("p--f", "COMPLETED", "done", "A.owl", now);
        repository.upsertMetadata("p--f", Map.of("b", 2), "UPLOADED", now);

        ProjectDocument read = repository.findById("p--f").orElseThrow();
        assertEquals("COMPLETED", read.getStatus());
        assertEquals(Map.of("b", 2), read.getMetadata());
    }

    @Test
    void importProgressMergesIntoExistingMetadataWithoutTouchingOtherKeys() {
        Instant now = Instant.now();
        repository.upsertMetadata("p--f", Map.of("keep", "yes"), "UPLOADED", now);

        repository.upsertImportProgress("p--f", Map.of("progress", 40, "stage", "parse"), "Importing", now);
        repository.upsertImportProgress("p--f", Map.of("progress", 60, "stage", "index"), "Indexing", now);
        repository.upsertImportProgress("fresh", Map.of("progress", 5), "Starting", now);

        ProjectDocument read = repository.findById("p--f").orElseThrow();
        assertEquals("yes", read.getMetadata().get("keep"));
        assertEquals(Map.of("progress", 60, "stage", "index"), read.getMetadata().get("importProgress"));
        assertEquals("Indexing", read.getStatusMessage());
        ProjectDocument fresh = repository.findById("fresh").orElseThrow();
        assertEquals(Map.of("importProgress", Map.of("progress", 5)), fresh.getMetadata());
        assertNull(fresh.getStatus());
    }

    @Test
    void projectMetadataWriteKeepsTheFirstOwnerAndOnlyOverwritesWorkspaceAndParentWhenGiven() {
        Instant now = Instant.now();
        repository.upsertProjectMetadata("p--f", "UPLOADED", "m", "A.owl", "blob1", "first@x.org", "u1", "ws-1", "p", now);
        repository.upsertProjectMetadata("p--f", "COMPLETED", "m2", "A.owl", "blob2", "second@x.org", "u2", null, "", now);

        ProjectDocument read = repository.findById("p--f").orElseThrow();
        assertEquals("first@x.org", read.getOwnerEmail());
        assertEquals("u1", read.getOwnerId());
        assertEquals("ws-1", read.getWorkspaceId());
        assertEquals("p", read.getProjectId());
        assertEquals("blob2", read.getGridfsFileId());
        assertEquals("COMPLETED", read.getStatus());
    }

    @Test
    void blobIdAndStatusUpdatesOnlyTouchExistingRowsWhereStated() {
        Instant now = Instant.now();
        repository.upsertBlobId("p--f", "blob9", now);
        assertEquals("blob9", repository.findById("p--f").orElseThrow().getGridfsFileId());

        repository.updateStatus("ghost", "ERROR", "x", now);
        assertTrue(repository.findById("ghost").isEmpty());
        repository.updateStatus("p--f", "ERROR", "x", now);
        assertEquals("ERROR", repository.findById("p--f").orElseThrow().getStatus());
    }

    @Test
    void versionCountersStartFromZeroAndAreExactUnderManyThreads() throws Exception {
        Instant now = Instant.now();
        repository.save(file("p--f", "A", "o", now));
        assertEquals(0L, repository.incrementMutationVersion("ghost", now));

        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                List<Long> seen = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    seen.add(repository.incrementMutationVersion("p--f", Instant.now()));
                    repository.incrementMainGraphRevision("p--f", Instant.now());
                }
                return seen;
            }));
        }
        go.countDown();
        Set<Long> all = new HashSet<>();
        for (Future<List<Long>> future : futures) {
            all.addAll(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(threads * perThread, all.size());
        assertEquals((long) threads * perThread, repository.findById("p--f").orElseThrow().getMutationVersion());
        assertEquals((long) threads * perThread, repository.findById("p--f").orElseThrow().getMainGraphRevision());
    }
}
