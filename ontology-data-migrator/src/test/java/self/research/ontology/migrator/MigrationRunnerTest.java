package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import self.research.ontology.migrator.collections.UserMigration;
import self.research.ontology.migrator.core.Checkpoint;
import self.research.ontology.migrator.core.DedupPlan;
import self.research.ontology.migrator.core.IdCodec;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.MigrationRunner;
import self.research.ontology.migrator.support.Docs;
import self.research.ontology.migrator.support.InMemorySource;
import self.research.ontology.migrator.support.RecordingTarget;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {

    private static InMemorySource users(int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            docs.add(Docs.user(i, "u" + i + "@x.com", "2026-01-01T00:00:00Z"));
        }
        return new InMemorySource().with("users", docs);
    }

    @Test
    void writesOneTransactionPerBatchAndMovesTheCheckpoint() {
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = new MigrationRunner(users(25), target, 10, false, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(25, stats.read);
        assertEquals(25, stats.written);
        assertEquals(4, target.commits);
        Checkpoint cp = target.checkpoints.get("users");
        assertEquals(Checkpoint.DONE, cp.status());
        assertEquals(25, cp.written());
        assertEquals("oid:" + Docs.oid(25).toHexString(), cp.lastId());
    }

    @Test
    void aStoppedRunResumesAfterTheLastCommittedId() {
        RecordingTarget target = new RecordingTarget();
        InMemorySource source = users(25).failAfterReading(15);
        assertThrows(IllegalStateException.class, () -> new MigrationRunner(source, target, 10, false, false)
                .run(new UserMigration(), DedupPlan.empty()));
        Checkpoint stopped = target.checkpoints.get("users");
        assertEquals(Checkpoint.RUNNING, stopped.status());
        assertEquals(10, stopped.read());
        assertEquals("oid:" + Docs.oid(10).toHexString(), stopped.lastId());
        assertEquals(10, target.upserts("app_user").size());

        source.stopFailing();
        MigrationReport.CollectionStats resumed = new MigrationRunner(source, target, 10, false, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(15, resumed.read);
        assertEquals(25, target.upserts("app_user").size());
        assertEquals(25, target.checkpoints.get("users").written());
        assertEquals(Checkpoint.DONE, target.checkpoints.get("users").status());
    }

    @Test
    void freshIgnoresTheCheckpointAndStartsOver() {
        RecordingTarget target = new RecordingTarget();
        new MigrationRunner(users(5), target, 10, false, false).run(new UserMigration(), DedupPlan.empty());
        MigrationReport.CollectionStats again = new MigrationRunner(users(5), target, 10, false, true)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(5, again.read);
        MigrationReport.CollectionStats resumed = new MigrationRunner(users(5), target, 10, false, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(0, resumed.read);
    }

    @Test
    void aBadDocumentIsReportedAndTheRestStillMoves() {
        InMemorySource source = users(3).with("users", new Document("_id", Docs.oid(99)).append("email", "no-username@x.com"));
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = new MigrationRunner(source, target, 10, false, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(4, stats.read);
        assertEquals(3, stats.written);
        assertEquals(1, stats.failed);
        assertFalse(stats.reconciled());
        assertTrue(stats.failedExamples.get(0).contains(Docs.oid(99).toHexString()));
    }

    @Test
    void aBatchThatFailsToWriteIsRetriedOneDocumentAtATime() {
        RecordingTarget target = new RecordingTarget();
        target.failWhenKeyEquals = Docs.oid(3).toHexString();
        MigrationReport.CollectionStats stats = new MigrationRunner(users(5), target, 10, false, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(4, stats.written);
        assertEquals(1, stats.failed);
        assertEquals(4, target.upserts("app_user").size());
        assertEquals(5, target.checkpoints.get("users").read());
    }

    @Test
    void dryRunMapsEverythingAndWritesNothing() {
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = new MigrationRunner(users(12), target, 5, true, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertEquals(12, stats.read);
        assertEquals(12, stats.written);
        assertEquals(0, target.commits);
        assertTrue(target.writes.isEmpty());
    }

    @Test
    void aMissingCollectionIsReportedAsEmpty() {
        MigrationReport.CollectionStats stats = new MigrationRunner(new InMemorySource(), new RecordingTarget(), 10, false, false)
                .run(new UserMigration(), DedupPlan.empty());
        assertTrue(stats.sourceMissing);
        assertEquals(0, stats.read);
    }

    @Test
    void collectionsThatMixObjectIdAndStringIdsResumeWithoutLosingDocuments() {
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            docs.add(Docs.user(i, "o" + i + "@x.com", "2026-01-01T00:00:00Z"));
            docs.add(new Document("_id", "str-" + i).append("username", "s" + i).append("email", "s" + i + "@x.com"));
        }
        InMemorySource source = new InMemorySource().with("users", docs).failAfterReading(7);
        RecordingTarget target = new RecordingTarget();
        assertThrows(IllegalStateException.class, () -> new MigrationRunner(source, target, 4, false, false)
                .run(new UserMigration(), DedupPlan.empty()));
        source.stopFailing();
        new MigrationRunner(source, target, 4, false, false).run(new UserMigration(), DedupPlan.empty());
        assertEquals(12, target.upserts("app_user").stream().map(u -> u.values().get("id")).distinct().count());
    }

    @Test
    void checkpointIdsRoundTrip() {
        for (Object id : new Object[]{Docs.oid(5), "abc:def", 7, 8L}) {
            assertEquals(id, IdCodec.decode(IdCodec.encode(id)));
        }
    }
}
