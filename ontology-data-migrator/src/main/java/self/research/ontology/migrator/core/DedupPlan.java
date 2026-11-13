package self.research.ontology.migrator.core;

import org.bson.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class DedupPlan {

    private record Candidate(String id, Instant recency) {
        boolean newerThan(Candidate other) {
            int c = recency.compareTo(other.recency);
            return c != 0 ? c > 0 : id.compareTo(other.id) > 0;
        }
    }

    private final Map<String, MigrationReport.Dropped> losers;

    private DedupPlan(Map<String, MigrationReport.Dropped> losers) {
        this.losers = losers;
    }

    public static DedupPlan empty() {
        return new DedupPlan(new HashMap<>());
    }

    public static DedupPlan build(SourceReader source, CollectionMigration migration, int batchSize) {
        Map<String, MigrationReport.Dropped> losers = new HashMap<>();
        if (migration.uniqueRules().isEmpty() || !source.exists(migration.sourceCollection())) {
            return new DedupPlan(losers);
        }
        for (UniqueRule rule : migration.uniqueRules()) {
            Map<String, Candidate> best = new HashMap<>();
            try (CloseableIterator<Document> cursor = source.scan(migration.sourceCollection(), null, batchSize)) {
                while (cursor.hasNext()) {
                    Document doc = cursor.next();
                    String id = Values.idText(doc.get("_id"));
                    if (id == null || !migration.accepts(doc) || migration.skipReason(doc) != null || losers.containsKey(id)) {
                        continue;
                    }
                    String key = rule.key().apply(doc);
                    if (key == null) {
                        continue;
                    }
                    Candidate mine = new Candidate(id, migration.recency(doc));
                    Candidate other = best.get(key);
                    if (other == null) {
                        best.put(key, mine);
                    } else if (mine.newerThan(other)) {
                        best.put(key, mine);
                        losers.put(other.id(), dropped(other.id(), rule, mine.id()));
                    } else {
                        losers.put(id, dropped(id, rule, other.id()));
                    }
                }
            }
        }
        return new DedupPlan(losers);
    }

    private static MigrationReport.Dropped dropped(String id, UniqueRule rule, String keptId) {
        return new MigrationReport.Dropped(id, rule.name(), "repeated " + rule.name() + ", newest row kept: " + keptId);
    }

    public MigrationReport.Dropped loserFor(Document doc) {
        String id = Values.idText(doc.get("_id"));
        return id == null ? null : losers.get(id);
    }

    public int size() {
        return losers.size();
    }
}
