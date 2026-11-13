package self.research.ontology.migrator.core;

import org.bson.Document;

import java.time.Instant;
import java.util.List;

public interface CollectionMigration {

    String name();

    String sourceCollection();

    List<Write> map(Document document);

    default boolean accepts(Document document) {
        return true;
    }

    default void prepare(SourceReader source) {
    }

    default Skip skipReason(Document document) {
        return null;
    }

    default List<UniqueRule> uniqueRules() {
        return List.of();
    }

    default Instant recency(Document document) {
        return Values.recency(document, "updatedAt", "createdAt", "recordedAt", "receivedAt", "uploadedAt",
                "publishedAt", "lastInstalledAt", "timestamp");
    }
}
