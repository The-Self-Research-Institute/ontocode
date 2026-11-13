package self.research.ontology.migrator.core;

import org.bson.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Verification {

    private final SourceReader source;
    private final TargetReader target;
    private final int batchSize;
    private final int sampleSize;

    public Verification(SourceReader source, TargetReader target, int batchSize, int sampleSize) {
        this.source = source;
        this.target = target;
        this.batchSize = batchSize;
        this.sampleSize = sampleSize;
    }

    public MigrationReport.VerifyStats verify(CollectionMigration migration, DedupPlan plan) {
        MigrationReport.VerifyStats stats = new MigrationReport.VerifyStats(migration.name());
        String collection = migration.sourceCollection();
        if (!source.exists(collection)) {
            return stats;
        }
        if (!migration.uniqueRules().isEmpty()) {
            try (CloseableIterator<Document> cursor = source.scan(collection, null, batchSize)) {
                while (cursor.hasNext()) {
                    Document doc = cursor.next();
                    if (relevant(migration, plan, doc)) {
                        stats.keysChecked++;
                        checkKeysExist(migration, doc, stats);
                    }
                }
            }
        }
        for (Document doc : source.sample(collection, sampleSize)) {
            if (relevant(migration, plan, doc)) {
                stats.sampled++;
                compareFields(migration, doc, stats);
            }
        }
        return stats;
    }

    private static boolean relevant(CollectionMigration migration, DedupPlan plan, Document doc) {
        return migration.accepts(doc) && migration.skipReason(doc) == null && plan.loserFor(doc) == null;
    }

    private void checkKeysExist(CollectionMigration migration, Document doc, MigrationReport.VerifyStats stats) {
        String id = Values.idText(doc.get("_id"));
        List<Write> writes;
        try {
            writes = migration.map(doc);
        } catch (RuntimeException e) {
            return;
        }
        for (Write w : writes) {
            if (w instanceof Write.Upsert u && target.fetch(u.table(), u.keys(), u.keyValues()).isEmpty()) {
                stats.keysMissing++;
                stats.example(id + ": no row in " + u.table());
                return;
            }
        }
    }

    private void compareFields(CollectionMigration migration, Document doc, MigrationReport.VerifyStats stats) {
        String id = Values.idText(doc.get("_id"));
        List<Write> writes;
        try {
            writes = migration.map(doc);
        } catch (RuntimeException e) {
            return;
        }
        for (Write w : writes) {
            if (!(w instanceof Write.Upsert u)) {
                continue;
            }
            Optional<Map<String, Object>> row = target.fetch(u.table(), u.keys(), u.keyValues());
            if (row.isEmpty()) {
                stats.fieldMismatches++;
                stats.example(id + ": no row in " + u.table());
                continue;
            }
            for (Map.Entry<String, Object> e : u.values().entrySet()) {
                if (!ValueCompare.same(e.getValue(), row.get().get(e.getKey()))) {
                    stats.fieldMismatches++;
                    stats.example(id + ": " + u.table() + "." + e.getKey() + " differs");
                }
            }
        }
    }
}
