package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;
import self.research.ontology.owlEditor.document.ProjectShare;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEditorStateRepositoriesTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcProjectShareRepository shares = new JdbcProjectShareRepository(jdbc);
    private final JdbcUserProjectPreferencesRepository preferences = new JdbcUserProjectPreferencesRepository(jdbc);
    private final JdbcPublicGraphVersionRepository versions = new JdbcPublicGraphVersionRepository(jdbc);
    private final JdbcProjectWriteLeaseRepository leases = new JdbcProjectWriteLeaseRepository(jdbc);
    private final JdbcProjectRecoveryLockRepository locks = new JdbcProjectRecoveryLockRepository(jdbc);
    private final JdbcAppMigrationRepository migrations = new JdbcAppMigrationRepository(jdbc);
    private final JdbcFileMetadataRepository fileMetadata = new JdbcFileMetadataRepository(jdbc);
    private final JdbcWorkspaceRepository workspaces = new JdbcWorkspaceRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from project_share");
        jdbc.update("delete from user_project_preference");
        jdbc.update("delete from project_public_graph_version");
        jdbc.update("delete from project_write_lease");
        jdbc.update("delete from project_recovery_lock");
        jdbc.update("delete from app_migration");
        jdbc.update("delete from file_metadata");
        jdbc.update("delete from workspace");
    }

    @Test
    void aSavedShareIsFoundByProjectOwnerLinkAndRecipient() {
        ProjectShare share = new ProjectShare("proj-1--f", "Owner@x.org", "view");
        share.addSharedEmail("a@x.org");
        shares.save(share);

        assertEquals("view", shares.findByProjectId("proj-1--f").orElseThrow().getPermission());
        assertEquals(List.of("a@x.org"), shares.findByProjectId("proj-1--f").orElseThrow().getSharedWithEmails());
        assertEquals(1, shares.findByOwnerEmail("Owner@x.org").size());
        assertTrue(shares.findByOwnerEmail("owner@x.org").isEmpty());
        assertEquals("proj-1--f", shares.findByShareLink(share.getShareLink()).orElseThrow().getProjectId());
        assertEquals(1, shares.findBySharedWithEmailsContaining("a@x.org").size());
        assertTrue(shares.findBySharedWithEmailsContaining("b@x.org").isEmpty());
        assertTrue(shares.existsByProjectId("proj-1--f"));
        assertFalse(shares.existsByProjectId("proj-2--f"));

        shares.delete(share);
        assertTrue(shares.findByProjectId("proj-1--f").isEmpty());
    }

    @Test
    void twoSharesForTheSameProjectAreRejected() {
        shares.save(new ProjectShare("proj-1--f", "o@x.org", "view"));

        assertThrows(DuplicateKeyException.class, () -> shares.save(new ProjectShare("proj-1--f", "o@x.org", "edit")));
    }

    @Test
    void addingAndRemovingRecipientsIsAtomicAndKeepsEveryRecipientUnderManyThreads() throws Exception {
        shares.save(new ProjectShare("proj-1--f", "o@x.org", "view"));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int n = t;
            futures.add(pool.submit(() -> {
                go.await();
                shares.addSharedEmail("proj-1--f", "user" + n + "@x.org", null, Instant.now());
                shares.addSharedEmail("proj-1--f", "user0@x.org", null, Instant.now());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<String> emails = shares.findByProjectId("proj-1--f").orElseThrow().getSharedWithEmails();
        assertEquals(threads, emails.size());
        assertEquals(threads, new HashSet<>(emails).size());
        assertEquals("view", shares.findByProjectId("proj-1--f").orElseThrow().getPermission());

        ProjectShare changed = shares.addSharedEmail("proj-1--f", "new@x.org", "edit", Instant.now()).orElseThrow();
        assertEquals("edit", changed.getPermission());
        assertEquals(threads + 1, changed.getSharedWithEmails().size());
        assertEquals(threads, shares.removeSharedEmail("proj-1--f", "new@x.org", Instant.now()).orElseThrow()
                .getSharedWithEmails().size());
        assertTrue(shares.addSharedEmail("ghost", "a@x.org", null, Instant.now()).isEmpty());
        assertTrue(shares.removeSharedEmail("ghost", "a@x.org", Instant.now()).isEmpty());
    }

    @Test
    void preferencesAreOnePerUserAndProjectAndTheLastWriteWins() throws Exception {
        assertTrue(preferences.findByUserEmailAndProjectId("a@x.org", "p1").isEmpty());

        preferences.saveSyncMode("a@x.org", "p1", "private", Instant.now());
        preferences.saveSyncMode("a@x.org", "p1", "public", Instant.now());
        preferences.saveSyncMode("b@x.org", "p1", "private", Instant.now());

        assertEquals("public", preferences.findByUserEmailAndProjectId("a@x.org", "p1").orElseThrow().getSyncMode());
        assertEquals("private", preferences.findByUserEmailAndProjectId("b@x.org", "p1").orElseThrow().getSyncMode());
        assertEquals(2, jdbc.queryForObject("select count(*) from user_project_preference", Integer.class));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> preferences.saveSyncMode("c@x.org", "p2", "public", Instant.now())));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject("select count(*) from user_project_preference where user_email = 'c@x.org'", Integer.class));
    }

    @Test
    void publicGraphVersionsCountExactlyUnderManyThreads() throws Exception {
        assertEquals(0L, versions.read("p1"));
        int threads = 12;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                List<Long> seen = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    seen.add(versions.increment("p1", Instant.now()));
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
        assertEquals(threads * perThread, versions.read("p1"));
        assertEquals(1L, versions.increment("p2", Instant.now()));
        assertEquals(threads * perThread, versions.read("p1"));
    }

    @Test
    void exactlyOneOfManyThreadsWinsALeaseAndAnExpiredLeaseCanBeTakenOver() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Instant expires = now.plusSeconds(30);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String token = "token-" + t;
            futures.add(pool.submit(() -> {
                go.await();
                if (leases.tryAcquire("p1", token, "node", now, expires) == ProjectWriteLeaseRepository.Acquisition.ACQUIRED) {
                    winners.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, winners.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from project_write_lease", Integer.class));

        String holder = jdbc.queryForObject("select owner_token from project_write_lease where project_id = 'p1'", String.class);
        assertEquals(ProjectWriteLeaseRepository.Acquisition.HELD,
                leases.tryAcquire("p1", "late", "node", now.plusSeconds(29), now.plusSeconds(59)));
        assertEquals(ProjectWriteLeaseRepository.Acquisition.TOOK_OVER,
                leases.tryAcquire("p1", "taker", "node", now.plusSeconds(31), now.plusSeconds(61)));
        assertFalse(leases.renew("p1", holder, now.plusSeconds(90)));
        assertTrue(leases.renew("p1", "taker", now.plusSeconds(90)));
        assertFalse(leases.release("p1", holder));
        assertTrue(leases.release("p1", "taker"));
        assertFalse(leases.release("p1", "taker"));
        assertEquals(ProjectWriteLeaseRepository.Acquisition.ACQUIRED,
                leases.tryAcquire("p1", "again", "node", now, expires));
    }

    @Test
    void recoveryLocksCanBeLockedAndReleasedOnceAtomically() throws Exception {
        Instant now = Instant.now();
        assertTrue(locks.findById("p1").isEmpty());
        locks.save(ProjectRecoveryLockDocument.builder().id("p1").locked(true).reason("rollback failed")
                .lockedAt(now).operationId("op-1").canRestore(true).updatedAt(now).build());

        ProjectRecoveryLockDocument read = locks.findById("p1").orElseThrow();
        assertTrue(read.isLocked());
        assertEquals("rollback failed", read.getReason());
        assertEquals("op-1", read.getOperationId());
        assertTrue(read.isCanRestore());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String actor = "actor-" + t;
            futures.add(pool.submit(() -> {
                go.await();
                if (locks.releaseIfLocked("p1", actor, "CLEARED", Instant.now())) {
                    released.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, released.get());
        ProjectRecoveryLockDocument after = locks.findById("p1").orElseThrow();
        assertFalse(after.isLocked());
        assertEquals("CLEARED", after.getReleaseAction());
        assertTrue(after.getReleasedBy().startsWith("actor-"));
        assertFalse(locks.releaseIfLocked("ghost", "a", "CLEARED", now));
    }

    @Test
    void migrationMarkersAreRecordedOnce() {
        assertFalse(migrations.isDone("prefix-backfill-v3"));

        migrations.markDone("prefix-backfill-v3", Map.of("updated", 3));
        migrations.markDone("prefix-backfill-v3", Map.of("updated", 4));

        assertTrue(migrations.isDone("prefix-backfill-v3"));
        assertEquals(1, jdbc.queryForObject("select count(*) from app_migration", Integer.class));
    }

    @Test
    void fileMetadataIsReadByFileIdAndDeletedOnesAreHiddenFromTheActiveLookup() {
        jdbc.update("insert into file_metadata (id, file_id, file_name, project_id, blob_id, is_deleted) values "
                + "('m1', 'f1', 'A.owl', 'proj-1', '65f0000000000000000000a1', false)");
        jdbc.update("insert into file_metadata (id, file_id, file_name, project_id, blob_id, is_deleted) values "
                + "('m2', 'f2', 'B.owl', 'proj-1', null, true)");

        var active = fileMetadata.findActiveByFileId("f1").orElseThrow();
        assertEquals("A.owl", active.fileName());
        assertEquals("proj-1", active.projectId());
        assertEquals("65f0000000000000000000a1", active.blobId());
        assertFalse(active.deleted());
        assertTrue(fileMetadata.findActiveByFileId("f2").isEmpty());
        assertTrue(fileMetadata.findByFileId("f2").orElseThrow().deleted());
        assertTrue(fileMetadata.findByFileId("f3").isEmpty());
    }

    @Test
    void workspacesAreReadByTheirBusinessId() {
        jdbc.update("insert into workspace (id, workspace_id, owner_id, subscription_plan) values ('w1', 'ws-1', 'owner-1', 'PRO')");

        var found = workspaces.findByWorkspaceId("ws-1").orElseThrow();
        assertEquals("owner-1", found.getOwnerId());
        assertEquals("PRO", found.getSubscriptionPlan());
        assertTrue(workspaces.findByWorkspaceId("ws-2").isEmpty());
    }
}
