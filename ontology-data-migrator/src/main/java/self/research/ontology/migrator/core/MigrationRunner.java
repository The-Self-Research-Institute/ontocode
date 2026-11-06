package self.research.ontology.migrator.core;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MigrationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

    private final SourceReader source;
    private final Target target;
    private final int batchSize;
    private final boolean dryRun;
    private final boolean fresh;

    public MigrationRunner(SourceReader source, Target target, int batchSize, boolean dryRun, boolean fresh) {
        this.source = source;
        this.target = target;
        this.batchSize = batchSize;
        this.dryRun = dryRun;
        this.fresh = fresh;
    }

    private record Prepared(Document doc, List<Write> writes) {
    }

    public MigrationReport.CollectionStats run(CollectionMigration migration, DedupPlan plan) {
        MigrationReport.CollectionStats stats = new MigrationReport.CollectionStats(migration.name(), migration.sourceCollection());
        Checkpoint cp = startingCheckpoint(migration);
        if (!source.exists(migration.sourceCollection())) {
            stats.sourceMissing = true;
            finish(cp, stats);
            return stats;
        }
        stats.sourceCount = source.count(migration.sourceCollection());
        try (CloseableIterator<Document> cursor = source.scan(migration.sourceCollection(), IdCodec.decode(cp.lastId()), batchSize)) {
            List<Document> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                batch.add(doc);
                if (batch.size() >= batchSize) {
                    cp = flush(migration, plan, batch, cp, stats);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                cp = flush(migration, plan, batch, cp, stats);
            }
        }
        finish(cp, stats);
        stats.cumulativeRead = cp.read();
        stats.cumulativeWritten = cp.written();
        stats.cumulativeSkipped = cp.skipped();
        return stats;
    }

    private Checkpoint startingCheckpoint(CollectionMigration migration) {
        Checkpoint fresh = new Checkpoint(migration.name(), null, 0, 0, 0, Checkpoint.RUNNING);
        if (dryRun || this.fresh || target == null) {
            return fresh;
        }
        return target.checkpoint(migration.name()).orElse(fresh);
    }

    private void finish(Checkpoint cp, MigrationReport.CollectionStats stats) {
        if (!dryRun && target != null && stats.failed == 0) {
            target.commit(List.of(), cp.advance(cp.lastId(), 0, 0, 0, Checkpoint.DONE));
        }
    }

    private Checkpoint flush(CollectionMigration migration, DedupPlan plan, List<Document> batch, Checkpoint cp,
                             MigrationReport.CollectionStats stats) {
        List<Prepared> ok = new ArrayList<>();
        long skipped = 0;
        for (Document doc : batch) {
            stats.read++;
            String id = Values.idText(doc.get("_id"));
            if (!migration.accepts(doc)) {
                stats.skippedOtherKind++;
                skipped++;
                continue;
            }
            Skip skip = migration.skipReason(doc);
            if (skip != null) {
                stats.recordDropped(new MigrationReport.Dropped(id, skip.rule(), skip.reason()));
                skipped++;
                continue;
            }
            MigrationReport.Dropped loser = plan.loserFor(doc);
            if (loser != null) {
                stats.recordDropped(loser);
                skipped++;
                continue;
            }
            try {
                ok.add(new Prepared(doc, migration.map(doc)));
            } catch (RuntimeException e) {
                stats.fail(id + ": " + e.getMessage());
                skipped++;
            }
        }
        String lastId = IdCodec.encode(batch.get(batch.size() - 1).get("_id"));
        if (lastId == null) {
            lastId = cp.lastId();
        }
        if (dryRun || target == null) {
            for (Prepared p : ok) {
                count(stats, p.writes());
            }
            return cp.advance(lastId, batch.size(), ok.size(), skipped, Checkpoint.RUNNING);
        }
        List<Write> all = new ArrayList<>();
        ok.forEach(p -> all.addAll(p.writes()));
        try {
            target.commit(all, cp.advance(lastId, batch.size(), ok.size(), skipped, Checkpoint.RUNNING));
            ok.forEach(p -> count(stats, p.writes()));
            return cp.advance(lastId, batch.size(), ok.size(), skipped, Checkpoint.RUNNING);
        } catch (RuntimeException batchFailure) {
            LOG.warn("[{}] A batch of {} failed to write, retrying its documents one by one", migration.name(), ok.size());
            return writeOneByOne(ok, cp, lastId, batch.size(), skipped, stats);
        }
    }

    private Checkpoint writeOneByOne(List<Prepared> ok, Checkpoint cp, String lastId, int batchRead, long skipped,
                                     MigrationReport.CollectionStats stats) {
        long written = 0;
        for (Prepared p : ok) {
            try {
                target.commit(p.writes(), null);
                count(stats, p.writes());
                written++;
            } catch (RuntimeException e) {
                stats.fail(Values.idText(p.doc().get("_id")) + ": write failed: " + firstLine(e.getMessage()));
                skipped++;
            }
        }
        Checkpoint next = cp.advance(lastId, batchRead, written, skipped, Checkpoint.RUNNING);
        target.commit(List.of(), next);
        return next;
    }

    private static void count(MigrationReport.CollectionStats stats, List<Write> writes) {
        stats.written++;
        stats.rowsWritten += writes.stream().filter(w -> w instanceof Write.Upsert).count();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
